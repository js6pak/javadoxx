import com.squareup.javapoet.*
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor
import javax.lang.model.element.Modifier

private abstract class JavaState : State() {
    class Annotation : Type()
    class Modifiers : State()
    class ReturnType : Type()
    class Name : State()

    class Parameter : Type() {
        fun toSpec(): ParameterSpec {
            if (parameterName == null) {
                throw Exception("parameterName has to be set to create ParameterSpec")
            }

            return ParameterSpec.builder(toTypeName(), parameterName).build()
        }

        var parameterName: String? = null
    }

    class Complete : State()
}

fun parseMethodSignature(signature: Element, javadoc: String? = null, isConstructor: Boolean = false): MethodSpec {
    val modifiers = mutableListOf<Modifier>()
    var returnType: TypeName? = null
    var name: String? = null
    val parameters = mutableListOf<ParameterSpec>()

    var state: State = JavaState.Modifiers()

    NodeTraversor.traverse(object : NodeVisitor {
        override fun head(childNode: Node, depth: Int) {
            when (childNode) {
                is TextNode -> {
                    val parentNode = childNode.parentNode()
                    if (parentNode is Element) {
                        when (parentNode.tagName()) {
                            "a" -> return
                        }
                    }

                    val builder = StringBuilder()

                    fun eat() {
                        if (builder.isEmpty())
                            return

                        val keyword = builder.toString()
                        builder.clear()

                        if (state is JavaState.Modifiers) {
                            val modifier = when (keyword) {
                                "public" -> Modifier.PUBLIC
                                "protected" -> Modifier.PROTECTED
                                "private" -> Modifier.PRIVATE
                                "abstract" -> Modifier.ABSTRACT
                                "static" -> Modifier.STATIC
                                "final" -> Modifier.FINAL
                                "synchronized" -> Modifier.SYNCHRONIZED
                                else -> {
                                    state = if (isConstructor) JavaState.Name() else JavaState.ReturnType()
                                    null
                                }
                            }

                            if (modifier != null) {
                                modifiers.add(modifier)
                                return
                            }
                        }

                        (state as? State.Type)?.let {
                            when {
                                it.name == null -> {
                                    it.name = ClassName.get("", keyword)
                                }
                                state is JavaState.ReturnType -> {
                                    returnType = it.toTypeName()
                                    state = JavaState.Name()
                                }
                                state is JavaState.Parameter -> {
                                    (state as JavaState.Parameter).parameterName = keyword
                                }
                                else -> {
                                    throw Exception("This shouldn't ever happen?")
                                }
                            }
                        }

                        if (state is JavaState.Name) {
                            name = keyword
                        }
                    }

                    for (c in childNode.text()) {
                        when (c) {
                            '@' -> {
                                if (state !is JavaState.Modifiers) {
                                    throw Exception("Unexpected @ during $state")
                                }

                                state = JavaState.Annotation()
                            }
                            '<' -> {
                                eat()

                                if (state !is State.Type) {
                                    throw Exception("Unexpected < during $state")
                                }

                                if ((state as State.Type).insideGeneric) {
                                    throw Exception("Unexpected double <")
                                }

                                (state as State.Type).insideGeneric = true
                                state = State.GenericArgument(state as State.Type)
                            }
                            '>' -> {
                                eat()

                                if (state !is State.GenericArgument) {
                                    throw Exception("Unexpected > during $state")
                                }

                                val parent = (state as State.GenericArgument).parent

                                if (!parent.insideGeneric) {
                                    throw Exception("Unexpected double >")
                                }

                                parent.insideGeneric = false
                                parent.typeArguments.add((state as State.GenericArgument).name ?: throw Exception("Invalid generic"))
                                state = parent
                            }
                            '(' -> {
                                eat()

                                if (state !is JavaState.Name) {
                                    throw Exception("Unexpected ( during $state")
                                }

                                state = JavaState.Parameter()
                            }
                            ',', ')' -> {
                                eat()

                                if (state !is JavaState.Parameter) {
                                    throw Exception("Unexpected $c during $state")
                                }

                                if (c == ',' || (state as JavaState.Parameter).parameterName != null) {
                                    parameters.add((state as JavaState.Parameter).toSpec())
                                }

                                if (c == ')') {
                                    state = JavaState.Complete()
                                }
                            }
                            ' ' -> eat()
                            else -> {
                                builder.append(c)
                            }
                        }
                    }

                    eat()
                }
                is Element -> {
                    when (childNode.tagName()) {
                        "br" -> {
                            if (state !is JavaState.Annotation) {
                                throw Exception("Unexpected <br> during $state")
                            }

                            state = JavaState.Modifiers()
                        }
                        "a" -> {
                            val className = ClassReference(childNode).className

                            if (state is JavaState.Modifiers) {
                                state = JavaState.ReturnType()
                            }

                            if (state is State.Type) {
                                (state as State.Type).name = className
                            }
                        }
                    }
                }
            }
        }

        override fun tail(node: Node, depth: Int) {
        }
    }, signature)

    if (state !is JavaState.Complete) {
        throw Exception("Unexpected $state")
    }

    return (if (isConstructor) MethodSpec.constructorBuilder() else MethodSpec.methodBuilder(name)).apply {
        addModifiers(*modifiers.toTypedArray())
        if (!isConstructor) returns(returnType)
        addParameters(parameters)
        if (!modifiers.contains(Modifier.ABSTRACT)) {
            addCode("""throw new RuntimeException("Stub!");""")
        }
        if (javadoc != null && javadoc.isNotBlank()) addJavadoc("\$L", javadoc)
    }.build()
}
