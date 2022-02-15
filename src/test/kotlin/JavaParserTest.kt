import com.squareup.javapoet.ClassName
import com.squareup.javapoet.ParameterizedTypeName
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import javax.lang.model.element.Modifier
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class JavaParserTest {
    private fun parse(html: String): Element {
        return Jsoup.parseBodyFragment("""<pre class="api-signature">$html</pre>""").select(".api-signature").single()
    }

    @Test
    fun publicVoid() {
        val methodSpec = parseMethodSignature(parse("""public void publicVoid ()"""))

        assertContentEquals(setOf(Modifier.PUBLIC), methodSpec.modifiers?.asIterable())
        assertEquals("void", methodSpec.returnType.toString())
        assertEquals("publicVoid", methodSpec.name)
        assertTrue(methodSpec.parameters.isEmpty())
    }

    @Test
    fun publicListString() {
        val methodSpec = parseMethodSignature(parse("""public List&lt;String&gt; publicListString ()"""))

        assertContentEquals(setOf(Modifier.PUBLIC), methodSpec.modifiers?.asIterable())
        assertEquals(ParameterizedTypeName.get(ClassName.get("", "List"), ClassName.get("", "String")), methodSpec.returnType)
        assertEquals("publicListString", methodSpec.name)
        assertTrue(methodSpec.parameters.isEmpty())
    }

    @Test
    fun publicVoidString() {
        val methodSpec = parseMethodSignature(parse("""public void publicVoidString (String hello)"""))

        assertContentEquals(setOf(Modifier.PUBLIC), methodSpec.modifiers?.asIterable())
        assertEquals("void", methodSpec.returnType.toString())
        assertEquals("publicVoidString", methodSpec.name)

        methodSpec.parameters.apply {
            assertEquals(1, methodSpec.parameters.count())

            single().apply {
                assertEquals("hello", name)
                assertEquals("String", type.toString())
            }
        }
    }
}
