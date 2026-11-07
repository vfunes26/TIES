package es.citaasistente.app.data

import android.content.Context

/** Quién está usando la app ahora mismo. La sesión se mantiene hasta que se pulsa «Cerrar sesión». */
object Session {

    fun role(ctx: Context): Role? =
        try {
            Role.valueOf(AuthPrefs.get(ctx).sessionRole)
        } catch (e: Exception) {
            null
        }

    fun isLoggedIn(ctx: Context): Boolean = role(ctx) != null

    fun isMaster(ctx: Context): Boolean = role(ctx) == Role.MASTER

    fun login(ctx: Context, role: Role) {
        AuthPrefs.get(ctx).sessionRole = role.name
    }

    fun logout(ctx: Context) {
        AuthPrefs.get(ctx).sessionRole = ""
    }

    fun label(ctx: Context): String = role(ctx)?.let { Roles.label(it) } ?: "Sin sesión"

    /** Línea que se antepone a los avisos de Telegram/correo para que el master sepa quién los generó. */
    fun tag(ctx: Context): String =
        if (role(ctx) == Role.USER) "👤 ${Roles.label(Role.USER)}\n" else ""

    fun tagInline(ctx: Context): String =
        if (role(ctx) == Role.USER) "👤 ${Roles.label(Role.USER)} · " else ""
}
