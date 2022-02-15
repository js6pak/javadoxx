import com.squareup.javapoet.ClassName
import com.squareup.javapoet.ParameterizedTypeName
import com.squareup.javapoet.TypeName

abstract class State {
    abstract class Type : State() {
        fun toTypeName(): TypeName {
            if (name == null) {
                throw Exception("Name has to be set to create TypeName")
            }

            if (typeArguments.isEmpty()) {
                return name!!
            }

            return ParameterizedTypeName.get(name, *typeArguments.toTypedArray())
        }

        var name: ClassName? = null
        val typeArguments: MutableList<TypeName> = mutableListOf()
        var insideGeneric = false
    }

    class GenericArgument(val parent: Type) : Type()
}