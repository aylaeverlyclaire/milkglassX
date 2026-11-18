package com.milkglass.app

import com.milkglass.app.core.data.SeedData
import com.milkglass.app.core.data.Translator
import com.milkglass.app.core.data.initialState
import com.milkglass.app.core.storage.PersistedState

object TestSeed {
    val state: (Translator, Long) -> PersistedState = { tr, boot ->
        SeedData.initialState(tr, SeedData.conversations(TestSupport.data.seedConversationsRaw, boot), boot)
    }
}
