package org.openclub.api.members

import java.time.LocalDate

/** Shared validation for member creation and details editing; database rules remain authoritative. */
internal object MemberFieldValidation {
    fun birthDate(value: LocalDate): LocalDate = value.also {
        require(it.isAfter(LocalDate.of(1850, 1, 1))) { "Invalid birth date" }
        require(!it.isAfter(LocalDate.now())) { "Birth date cannot be in the future" }
    }

    fun name(value: String): String = value.trim().also {
        require(it.length in 1..200 && it.none(Character::isISOControl)) { "Invalid member name" }
    }

    fun email(value: String?): String? = value?.trim()?.takeIf(String::isNotEmpty).also {
        if (it != null) {
            val at = it.indexOf('@')
            require(it.length in 3..320 && at in 1 until it.lastIndex &&
                it.indexOf('@', at + 1) == -1 &&
                it.none { char -> char.isWhitespace() || Character.isISOControl(char) }
            ) { "Invalid email field" }
        }
    }

    fun phone(value: String?): String? = value?.trim()?.takeIf(String::isNotEmpty).also {
        require(it == null || (it.length <= 40 && it.none(Character::isISOControl))) { "Invalid phone field" }
    }

    fun gender(value: String?): String? = value?.trim()?.takeIf(String::isNotEmpty).also {
        require(it == null || (it.length <= 200 && it.none(Character::isISOControl))) { "Invalid gender field" }
    }
}
