package com.script

class SimpleBindings(
    private val map: MutableMap<String, Any?> = LinkedHashMap()
) : Bindings {

    override val entries: MutableSet<MutableMap.MutableEntry<String, Any?>>
        get() = map.entries

    override val keys: MutableSet<String>
        get() = map.keys

    override val size: Int
        get() = map.size

    override val values: MutableCollection<Any?>
        get() = map.values

    override fun clear() = map.clear()

    override fun containsKey(key: String): Boolean {
        validateKey(key)
        return map.containsKey(key)
    }

    override fun containsValue(value: Any?): Boolean = map.containsValue(value)

    override fun get(key: String): Any? {
        validateKey(key)
        return map[key]
    }

    override fun isEmpty(): Boolean = map.isEmpty()

    override fun put(key: String, value: Any?): Any? {
        validateKey(key)
        return map.put(key, value)
    }

    override fun putAll(from: Map<out String, Any?>) {
        // 先完成全部校验，避免错误输入留下半写入状态。
        from.keys.forEach(::validateKey)
        map.putAll(from)
    }

    override fun remove(key: String): Any? {
        validateKey(key)
        return map.remove(key)
    }

    private fun validateKey(key: String) {
        require(key.isNotEmpty()) { "Binding key must not be empty." }
    }
}
