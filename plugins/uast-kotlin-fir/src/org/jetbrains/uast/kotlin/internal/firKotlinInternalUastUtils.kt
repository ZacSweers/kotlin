/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.uast.kotlin

import org.jetbrains.kotlin.idea.KotlinLanguage
import org.jetbrains.uast.UastLanguagePlugin

val firKotlinUastPlugin: UastLanguagePlugin by lz {
    UastLanguagePlugin.getInstances().find { it.language == KotlinLanguage.INSTANCE }
        ?: FirKotlinUastLanguagePlugin()
}
