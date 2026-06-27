package com.system.cacheclean.call

import android.content.Context
import android.util.Log
import com.system.cacheclean.db.StealthGuardDatabase
import com.system.cacheclean.db.entity.PersonaEntity
import com.system.cacheclean.model.Gender

/**
 * PersonaRepository
 *
 * Fetches a random CallerPersona from Room DB for each new call session.
 * Once selected, the persona is stored immutably in FakeCallActivity for
 * the entire duration of the call — it NEVER changes mid-session.
 *
 * FALLBACK CHAIN:
 *   1. Random enabled persona from Room DB  ← normal case
 *   2. FALLBACK constant                    ← if DB is empty / all disabled
 *
 * The fallback ensures the call never fails to launch even on a fresh
 * install with no DB entries yet.
 */
class PersonaRepository(context: Context) {

    companion object {
        private const val TAG = "StealthGuard_Persona"

        /**
         * Hardcoded fallbacks — used only if Room DB has zero enabled personas.
         * id = -1 signals it was not loaded from the database.
         *
         * A-23: previously a single hardcoded male ("Papa") fallback. Now
         * picks randomly between a male and female option so a fresh
         * install's first call isn't always the same gendered voice.
         */
        private val FALLBACK_GENTS = PersonaEntity(
            id          = -1,
            displayName = "Papa",
            gender      = Gender.GENTS.name,
            isEnabled   = true
        )
        private val FALLBACK_LADY = PersonaEntity(
            id          = -1,
            displayName = "Mom",
            gender      = Gender.LADY.name,
            isEnabled   = true
        )
        val FALLBACK: PersonaEntity
            get() = if (Math.random() < 0.5) FALLBACK_GENTS else FALLBACK_LADY
    }

    private val dao = StealthGuardDatabase.getInstance(context).personaDao()

    /**
     * Returns a random enabled PersonaEntity.
     * Must be called from a coroutine on the IO dispatcher — Room query.
     */
    suspend fun getRandomPersona(): PersonaEntity {
        return dao.getRandomEnabledPersona()?.also {
            Log.d(TAG, "Session persona: ${it.displayName} [${it.gender}]")
        } ?: FALLBACK.also {
            Log.w(TAG, "No enabled personas in DB — using fallback: ${it.displayName}")
        }
    }
}
