package com.keyscafe.unlock

import java.lang.reflect.Method

object Reflect {

    fun findClassIfExists(name: String, cl: ClassLoader): Class<*>? =
        runCatching { Class.forName(name, false, cl) }.getOrNull()

    fun method(cls: Class<*>, name: String, vararg types: Class<*>): Method? =
        runCatching { cls.getDeclaredMethod(name, *types).apply { isAccessible = true } }.getOrNull()

    // 递归查找方法
    fun methodInHierarchy(cls: Class<*>, name: String, vararg types: Class<*>): Method? {
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            method(c, name, *types)?.let { return it }
            c = c.superclass
        }
        return null
    }

    fun staticBoolean(cls: Class<*>, name: String): Boolean =
        cls.getDeclaredField(name).apply { isAccessible = true }.getBoolean(null)

    fun setStaticBoolean(cls: Class<*>, name: String, value: Boolean) {
        cls.getDeclaredField(name).apply { isAccessible = true }.setBoolean(null, value)
    }

    private fun isCompatible(paramType: Class<*>, arg: Any?): Boolean {
        if (arg == null) return !paramType.isPrimitive
        if (paramType.isInstance(arg)) return true
        return when (paramType) {
            java.lang.Boolean.TYPE -> arg is Boolean
            java.lang.Integer.TYPE -> arg is Int
            java.lang.Long.TYPE -> arg is Long
            java.lang.Float.TYPE -> arg is Float
            java.lang.Double.TYPE -> arg is Double
            java.lang.Byte.TYPE -> arg is Byte
            java.lang.Short.TYPE -> arg is Short
            java.lang.Character.TYPE -> arg is Char
            else -> false
        }
    }

    fun callStatic(cls: Class<*>, name: String, vararg args: Any?): Any? {
        val m = cls.declaredMethods.firstOrNull {
            it.name == name && it.parameterTypes.size == args.size &&
                it.parameterTypes.indices.all { i -> isCompatible(it.parameterTypes[i], args[i]) }
        } ?: throw NoSuchMethodException("${cls.name}#$name(${args.size} args)")
        m.isAccessible = true
        return m.invoke(null, *args)
    }

    fun call(obj: Any, name: String, vararg args: Any?): Any? {
        val cls = obj.javaClass
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            val m = c.declaredMethods.firstOrNull {
                it.name == name && it.parameterTypes.size == args.size &&
                    it.parameterTypes.indices.all { i -> isCompatible(it.parameterTypes[i], args[i]) }
            }
            if (m != null) {
                m.isAccessible = true
                return m.invoke(obj, *args)
            }
            c = c.superclass
        }
        throw NoSuchMethodException("${cls.name}#$name(${args.size} args)")
    }

    fun field(obj: Any, name: String): Any? {
        var c: Class<*>? = obj.javaClass
        while (c != null && c != Any::class.java) {
            val curr = c
            runCatching {
                val f = curr.getDeclaredField(name).apply { isAccessible = true }
                return f.get(obj)
            }
            c = c.superclass
        }
        return null
    }
}
