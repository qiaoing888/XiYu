package com.keyscafe.unlock

import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.ArrayList

/** Keys Cafe 星标按键绕过 */
object KeysCafeHooks {

    private val FRAGMENT_CANDIDATES get() = arrayOf(
        XyCore.get(20),
    )

    fun install(module: XposedModule, classLoader: ClassLoader) {
        for (name in FRAGMENT_CANDIDATES) {
            val cls = Reflect.findClassIfExists(name, classLoader)
            if (cls == null) {
                XLog.i("Keys Cafe: 找不到 $name")
                continue
            }
            hookValidationMethods(module, cls)
            return
        }
    }

    // 挂钩校验方法
    private fun hookValidationMethods(module: XposedModule, cls: Class<*>) {
        val targets = cls.declaredMethods.filter {
            it.returnType == java.lang.Void.TYPE &&
                it.parameterTypes.size == 1 &&
                it.parameterTypes[0] == java.lang.Boolean.TYPE
        }
        if (targets.isEmpty()) {
            XLog.i("Keys Cafe: ${cls.simpleName} 未找到 void(boolean) 校验方法")
            return
        }
        XLog.i("Keys Cafe: 命中 ${targets.size} 个校验方法")
        targets.forEach { method ->
            XLog.safe("keyscafe.${method.name}") {
                module.hook(method).intercept { chain ->
                    clearMandatory(chain.thisObject)
                    val r = chain.proceed()
                    clearMandatory(chain.thisObject)
                    r
                }
            }
        }
    }

    // 清空必填键列表
    private fun clearMandatory(root: Any?) {
        if (!ConfigStore.keysCafeBypass) return
        root ?: return
        runCatching {
            for (owner in candidates(root)) {
                for (f in allFields(owner.javaClass)) {
                    if (Modifier.isStatic(f.modifiers)) continue
                    if (runCatching { f.isAccessible = true }.isFailure) continue
                    val value = runCatching { f.get(owner) }.getOrNull() ?: continue

                    if (value is Collection<*>) {
                        clearIfStringList(value, owner.javaClass.simpleName, f.name)
                    }
                }
            }
        }.onFailure { XLog.e("clearMandatory 失败", it) }
    }

    // 遍历候选对象
    private fun candidates(root: Any): List<Any> {
        val list = ArrayList<Any>()
        list.add(root)
        for (f in allFields(root.javaClass)) {
            if (Modifier.isStatic(f.modifiers)) continue
            if (!isAppClass(f.type)) continue
            if (runCatching { f.isAccessible = true }.isFailure) continue
            runCatching { f.get(root) }.getOrNull()?.let { list.add(it) }
        }
        return list
    }

    private fun clearIfStringList(value: Collection<*>, ownerName: String, fieldName: String) {
        if (value.isEmpty()) return
        val first = value.firstOrNull() ?: return
        if (first !is String) return
        if (value.size > 200) return
        runCatching {
            val count = value.size
            @Suppress("UNCHECKED_CAST")
            (value as MutableCollection<Any>).clear()
            XLog.i("清空必填键: $ownerName.$fieldName ($count 项)")
        }
    }

    // 获取类及父类所有字段
    private fun allFields(cls: Class<*>): List<Field> {
        val out = ArrayList<Field>()
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            out.addAll(c.declaredFields)
            c = c.superclass
        }
        return out
    }

    private fun isAppClass(type: Class<*>): Boolean {
        val n = type.name
        return !n.startsWith("java.") && !n.startsWith("android.") &&
            !n.startsWith("kotlin.") && !n.startsWith("androidx.")
    }

}
