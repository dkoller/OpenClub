package org.openclub.api.members

/** Shared validation for member creation and details editing; database rules remain authoritative. */
internal object MemberFieldValidation {
    fun name(value: String): String = value.trim().also {
        require(it.length in 1..200) { "Invalid member name" }
    }

    fun email(value: String?): String? = value?.trim()?.takeIf(String::isNotEmpty).also {
        require(it == null || it.length in 3..320) { "Invalid email length" }
    }

    fun phone(value: String?): String? = value?.trim()?.takeIf(String::isNotEmpty).also {
        require(it == null || it.length <= 40) { "Invalid phone length" }
    }

    fun gender(value: String?): String? = value?.trim()?.takeIf(String::isNotEmpty).also {
        require(it == null || it.length <= 200) { "Invalid gender length" }
    }
}
