import com.squareup.javapoet.*
import org.jsoup.internal.StringUtil
import org.jsoup.nodes.*
import java.net.URI
import java.net.URLDecoder
import javax.lang.model.element.Modifier

val linkRegex = """(/android)?/reference/(?<fullName>[\w/.]+)""".toRegex()

val constantSignatureRegex = """public static final (?<type>int|String) (?<name>\w+)""".toRegex()
val constantValueRegex = mapOf(
    "int" to """Constant Value: (?<value>-?\d+) \(0x[0-9a-fA-F]+\)""".toRegex(),
    "String" to """Constant Value: (?<value>"\w+")""".toRegex(),
)

data class ClassReference(
    val name: String,
    val link: String
) {
    constructor(element: Element) : this(element.text(), element.attr("href"))

    val fullName: String = (linkRegex.matchEntire(URI(link).path.removeSuffix(".html")) ?: throw Exception("Failed to match $link")).groups["fullName"]!!.value.replace('/', '.')
    val className: ClassName = ClassName.bestGuess(fullName)
}

fun parseClassIndex(html: Document): List<ClassReference> {
    return html.select("table tr td.jd-linkcol a")
        .map { ClassReference(it) }
}

enum class Section {
    CONSTANTS,
    FIELDS,
    CONSTRUCTORS,
    METHODS,
}

fun extractJavadoc(root: Element, variant: Variant): String {
    val accum = StringUtil.borrowBuilder()

    for (paragraph in root.select(if (variant == Variant.GOOGLE) "p, ul" else "div.jd-tagdescr > p").takeWhile { it.childNodeSize() > 0 }) {
        when (paragraph.tagName()) {
            "p" -> {
                for (node in paragraph.childNodes()) {
                    if (node is TextNode) {
                        accum.append(node.text())
                    } else if (node is Element) {
                        when (node.tagName()) {
                            "a" -> {
                                accum.append("""<a href="${node.attr("href")}">${node.wholeText()}</a>""")
                            }

                            "code" -> {
                                val a = node.select("a").singleOrNull()

                                if (a != null) {
                                    accum.append(
                                        "{@link ${
                                            URLDecoder.decode(a.attr("href"), Charsets.UTF_8)
                                                .replace(".html", "")
                                                .removePrefix("/android/reference/")
                                                .removePrefix("/reference/")
                                                .replace('/', '.')
                                                .replace("<.+>".toRegex(), "")
                                        }}"
                                    )
                                } else {
                                    accum.append(node.outerHtml().trim())
                                }
                            }
                        }
                    }
                }
            }

            "ul" -> {
                accum.append(paragraph.outerHtml().trim())
            }
        }

        accum.appendLine()
    }

    return StringUtil.releaseBuilder(accum).trim { it <= ' ' }
}

enum class Variant {
    // developer.android.com
    ANDROID,

    // developers.google.com
    GOOGLE,
}

private abstract class HtmlState : State() {
    class SuperType : Type()
    class InterfaceType : Type()
}

fun parseClass(fullName: ClassName, html: Document, variant: Variant): TypeSpec.Builder {
    val modifiers = mutableListOf<Modifier>()
    var kind: TypeSpec.Kind? = null
    var superclass: TypeName? = null
    val interfaces = mutableListOf<TypeName>()

    when (variant) {
        Variant.ANDROID -> {
            for (element in html.select("code.api-signature")) {
                (element.childNode(0) as? TextNode)?.let { firstNode ->
                    val text = firstNode.text().trim()
                    if (text.isEmpty())
                        return@let

                    val extends = text.startsWith("extends")
                    val implements = text.startsWith("implements")

                    if (extends || implements) {
                        for (node in element.childNodes().drop(1)) {
                            if (node.outerHtml() == " ")
                                continue

                            val className = ClassName.bestGuess(
                                when (node) {
                                    is TextNode -> node.text()
                                    is Element -> ClassReference(node).fullName
                                    else -> throw Exception("Unrecognized node $node")
                                }
                            )

                            when (className.canonicalName()) {
                                "java.lang.annotation.Annotation" -> continue
                                "java.lang.Object" -> continue
                            }

                            when {
                                extends -> superclass = className
                                implements -> interfaces.add(className)
                            }
                        }
                    } else {
                        val split = firstNode.wholeText.trim().split('\n').map { it.trim() }

                        when (val access = split[0]) {
                            "public" -> modifiers.add(Modifier.PUBLIC)
                            else -> throw Exception("Unrecognized access modifier: $access")
                        }

                        if (split[1] != "")
                            when (split[1]) {
                                "static" -> modifiers.add(Modifier.STATIC)
                                else -> throw Exception("Unrecognized modifier: ${split[1]}")
                            }

                        if (split[2] != "")
                            when (split[2]) {
                                "final" -> modifiers.add(Modifier.FINAL)
                                else -> throw Exception("Unrecognized modifier: ${split[2]}")
                            }

                        if (split[3] != "")
                            when (split[3]) {
                                "abstract" -> modifiers.add(Modifier.ABSTRACT)
                                else -> throw Exception("Unrecognized modifier: ${split[3]}")
                            }

                        kind = when (val type = split[4]) {
                            "class" -> TypeSpec.Kind.CLASS
                            "interface" -> TypeSpec.Kind.INTERFACE
                            "@interface" -> TypeSpec.Kind.ANNOTATION
                            else -> throw Exception("Unrecognized kind: $type")
                        }
                    }
                }
            }
        }
        Variant.GOOGLE -> {
            val header = html.select("#jd-header").single()

            var name: String? = null

            var state: State? = null

            for (node in header.childNodes()) {
                when {
                    modifiers.isEmpty() -> {
                        for (modifier in (node as TextNode).text().trim().split(' ')) {
                            when (modifier) {
                                "public" -> modifiers.add(Modifier.PUBLIC)
                                "protected" -> modifiers.add(Modifier.PROTECTED)
                                "private" -> modifiers.add(Modifier.PRIVATE)
                                "abstract" -> modifiers.add(Modifier.ABSTRACT)
                                "static" -> modifiers.add(Modifier.STATIC)
                                "final" -> modifiers.add(Modifier.FINAL)
                                "synchronized" -> modifiers.add(Modifier.SYNCHRONIZED)

                                "class" -> kind = TypeSpec.Kind.CLASS
                                "interface" -> kind = TypeSpec.Kind.INTERFACE
                                "@interface" -> kind = TypeSpec.Kind.ANNOTATION
                            }
                        }
                    }

                    name == null -> {
                        name = (node as Element).text()
                    }

                    node is TextNode -> {
                        when (node.text().trim()) {
                            "implements" -> state = HtmlState.InterfaceType()
                            "extends" -> state = HtmlState.SuperType()
                            "<" -> {
                                if (state !is State.Type) {
                                    throw Exception("Unexpected < during $state")
                                }

                                if (state.insideGeneric) {
                                    throw Exception("Unexpected double <")
                                }

                                state.insideGeneric = true
                                state = State.GenericArgument(state)
                            }
                            ">" -> {
                                if (state !is State.GenericArgument) {
                                    throw Exception("Unexpected > during $state")
                                }

                                val parent = state.parent

                                if (!parent.insideGeneric) {
                                    throw Exception("Unexpected double >")
                                }

                                parent.insideGeneric = false
                                parent.typeArguments.add(state.name ?: throw Exception("Invalid generic"))
                                state = parent
                            }
                            "" -> {
                                when (state) {
                                    is HtmlState.InterfaceType -> {
                                        interfaces.add(state.toTypeName())
                                        state = HtmlState.InterfaceType()
                                    }
                                    is HtmlState.SuperType -> {
                                        superclass = state.toTypeName()
                                        state = null
                                    }
                                }
                            }
                            else -> throw Exception()
                        }
                    }

                    node is Element -> {
                        if (node.tagName() == "br")
                            continue

                        when (state) {
                            is State.Type -> state.name = ClassReference(node).className
                            else -> throw Exception("Bad state")
                        }
                    }
                }
            }

            when (state) {
                is HtmlState.InterfaceType -> if (state.name != null) interfaces.add(state.toTypeName())
                is HtmlState.SuperType -> superclass = state.toTypeName()
            }
        }
    }

    if (kind == null) {
        throw Exception("Failed to parse $fullName")
    }

    val builder = when (kind!!) {
        TypeSpec.Kind.CLASS -> TypeSpec.classBuilder(fullName)
        TypeSpec.Kind.INTERFACE -> TypeSpec.interfaceBuilder(fullName)
        TypeSpec.Kind.ENUM -> TypeSpec.enumBuilder(fullName)
        TypeSpec.Kind.ANNOTATION -> TypeSpec.annotationBuilder(fullName)
    }.apply {
        if (kind == TypeSpec.Kind.ANNOTATION) {
            modifiers.remove(Modifier.ABSTRACT)
        }

        addModifiers(*modifiers.toTypedArray())

        if (superclass != null) superclass(superclass)
        if (interfaces.isNotEmpty()) addSuperinterfaces(interfaces.distinct())

        when (variant) {
            Variant.ANDROID -> {
                var section: Section? = null

                for (node in html.select("div#jd-content").single().childNodes()) {
                    if (node is Comment) {
                        when (node.data.trim()) {
                            "Constants" -> section = Section.CONSTANTS
                            "Fields" -> section = Section.FIELDS
                            "Public ctors" -> section = Section.CONSTRUCTORS
                            "Public methdos" -> section = Section.METHODS
                        }
                        continue
                    }
                    if (node is Element && node.tagName() == "div") {
                        when (section) {
                            Section.CONSTANTS -> {
                                val signature = node.select("pre.api-signature").single().text()
                                val matchResult = constantSignatureRegex.matchEntire(signature) ?: throw Exception("Failed to match $signature")

                                val name = matchResult.groups["name"]!!.value
                                val type = matchResult.groups["type"]!!.value

                                val value =
                                    (constantValueRegex[type]!!.matchEntire(node.select("p").last()!!.text().trim()) ?: throw Exception("No value found for $name")).groups["value"]!!.value

                                val javadoc = extractJavadoc(node, variant)

                                addField(
                                    FieldSpec.builder(
                                        when (type) {
                                            "int" -> TypeName.INT
                                            "String" -> ClassName.get("java.lang", "String")
                                            else -> throw Exception("Unrecognized type $type")
                                        }, name, Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL
                                    ).apply {
                                        if (javadoc.isNotBlank()) addJavadoc("\$L", javadoc)
                                        initializer("\$L", value)
                                    }.build()
                                )
                            }
                            Section.METHODS, Section.CONSTRUCTORS -> {
                                val signature = node.select("pre.api-signature").single()

                                addMethod(parseMethodSignature(signature, extractJavadoc(node, variant), section == Section.CONSTRUCTORS))
                            }
                            else -> {}
                        }
                    }
                }
            }
            Variant.GOOGLE -> {
                for (element in html.select("section#constants > div.jd-details")) {
                    val type = element.select(".jd-details-title span.normal").single().text().split(" ").last()
                    val name = element.select(".jd-details-title strong").single().text()
                    val value = element.select("div.jd-tagdata > span:not(.jd-tagdescr)").single().text()

                    val javadoc = extractJavadoc(element, variant)

                    addField(
                        FieldSpec.builder(
                            when (type) {
                                "int" -> TypeName.INT
                                "long" -> TypeName.LONG
                                "String" -> ClassName.get("java.lang", "String")
                                else -> throw Exception("Unrecognized type $type")
                            }, name, Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL
                        ).apply {
                            if (javadoc.isNotBlank()) addJavadoc("\$L", javadoc)
                            initializer("\$L", value)
                        }.build()
                    )
                }

                for (element in html.select("section#public-methods > div.jd-details")) {
                    val signature = element.select(".jd-details-title").single()

                    addMethod(parseMethodSignature(signature, extractJavadoc(element, variant)))
                }
            }
        }
    }

    return builder
}
