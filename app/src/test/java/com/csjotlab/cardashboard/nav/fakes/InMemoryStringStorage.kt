package com.csjotlab.cardashboard.nav.fakes

import com.csjotlab.cardashboard.nav.data.StringStorage

class InMemoryStringStorage(var value: String? = null) : StringStorage {
    override fun read(): String? = value
    override fun write(value: String) { this.value = value }
}
