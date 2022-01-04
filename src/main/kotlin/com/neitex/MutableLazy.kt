package com.neitex

import kotlin.reflect.KProperty

internal fun <T> mutableLazy(initializer: () -> T) = Delegate(lazy(initializer))

internal class Delegate<T>(private val lazy: Lazy<T>) {
    private var value: T? = null

    operator fun getValue(thisRef: Any?, property: KProperty<*>): T {
        return value ?: lazy.getValue(thisRef, property)
    }

    operator fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        this.value = value
    }
}