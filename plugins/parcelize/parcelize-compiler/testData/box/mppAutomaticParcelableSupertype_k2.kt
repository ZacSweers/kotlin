// IGNORE_BACKEND_K1: ANY
// LANGUAGE: +MultiPlatformProjects
// WITH_STDLIB
// DIAGNOSTICS: -EXPECT_AND_ACTUAL_IN_THE_SAME_MODULE

// MODULE: m1-common
// FILE: common.kt

package test

import kotlinx.parcelize.Parcelize

expect interface CommonParcelable

@Parcelize
data class BareUser(val name: String)

@Parcelize
data class <!NO_PARCELABLE_SUPERTYPE{METADATA}!>WrappedUser<!>(val name: String) : CommonParcelable

@Parcelize
enum class AutomaticEnum {
    Entry,
}

// MODULE: m2-jvm()()(m1-common)
// FILE: android.kt

package test

actual typealias CommonParcelable = android.os.Parcelable

// MODULE: m3-jvm(m2-jvm)
// FILE: main.kt

@file:JvmName("TestKt")

package test

import android.os.Parcel
import android.os.Parcelable
import kotlinx.parcelize.parcelableCreator

private inline fun <reified T : Parcelable> roundTrip(value: T): T {
    val parcel = Parcel.obtain()
    try {
        value.writeToParcel(parcel, 0)
        val bytes = parcel.marshall()
        parcel.unmarshall(bytes, 0, bytes.size)
        parcel.setDataPosition(0)
        return parcelableCreator<T>().createFromParcel(parcel)
    } finally {
        parcel.recycle()
    }
}

fun box(): String {
    val bare = BareUser("bare")
    val wrapped = WrappedUser("wrapped")

    assert(roundTrip(bare) == bare)
    assert(roundTrip(wrapped) == wrapped)
    assert(roundTrip(AutomaticEnum.Entry) == AutomaticEnum.Entry)

    return "OK"
}
