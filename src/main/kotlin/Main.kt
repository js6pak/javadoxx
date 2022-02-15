import com.squareup.javapoet.ClassName
import com.squareup.javapoet.JavaFile
import com.squareup.javapoet.TypeSpec
import org.jsoup.Jsoup
import java.io.File
import java.io.FileNotFoundException
import java.net.URI
import java.net.URL

fun main(args: Array<String>) {
    // Examples
    // https://developer.android.com/
    // https://developers.google.com/android/
    // file:///home/js6pak/Downloads/billing-4.0.0-javadoc/
    // file:///home/js6pak/Downloads/play-services-games-22.0.1-javadoc/
    val baseUrl = args[0]

    // Examples
    // com.google.android.play.core
    // com.android.billingclient
    // com.google.android.gms.games
    val libraryName = args[1]

    val rootTypes = mutableMapOf<ClassName, TypeSpec.Builder>()
    val nestedTypes = mutableMapOf<ClassName, MutableList<Pair<ClassName, TypeSpec.Builder>>>()

    var variant: Variant
    val html = try {
        variant = Variant.ANDROID
        URL(baseUrl + "/reference/" + libraryName.replace('.', '/') + "/classes.html").readText()
    } catch (e: FileNotFoundException) {
        variant = Variant.GOOGLE
        URL(baseUrl + "/reference/" + libraryName.replace('.', '/') + "/package-summary.html").readText()
    }

    for (classReference in parseClassIndex(Jsoup.parse(html))) {
        val className = classReference.className
        val url = if (baseUrl.startsWith("file://")) URL(baseUrl + URI(classReference.link).path) else URI(baseUrl).resolve(classReference.link).toURL()
        val builder = parseClass(className, Jsoup.parse(url.readText()), variant)

        println("Found $className")

        val enclosingClassName = className.enclosingClassName()
        if (enclosingClassName != null) {
            nestedTypes.getOrPut(enclosingClassName) { mutableListOf() }.add(Pair(className, builder))
        } else {
            rootTypes[className] = builder
        }
    }

    fun nest(className: ClassName, type: TypeSpec.Builder) {
        nestedTypes[className]?.run {
            for ((nestedClassName, nestedType) in this) {
                nest(nestedClassName, nestedType)
                type.addType(nestedType.build())
            }
        }
    }

    for ((className, type) in rootTypes) {
        nest(className, type)

        val javaFile = JavaFile.builder(className.packageName(), type.build()).indent("    ").build()
        javaFile.writeTo(File("."))

        println("Wrote ${javaFile.typeSpec.name}.java")
    }
}