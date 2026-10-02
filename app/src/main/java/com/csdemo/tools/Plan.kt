package com.csdemo.tools

import android.content.Context
import androidx.compose.runtime.mutableStateOf

/**
 * 方案状态：基础方案 / Root 方案。
 * 首次启动需要先同意隐私政策，再选方案。
 * 状态用 SharedPreferences 持久化。
 */
object Plan {

    const val NONE = 0
    const val BASIC = 1
    const val ROOT = 2

    private const val PREFS = "csdemo_plan"
    private const val K_AGREED = "privacy_agreed"
    private const val K_PLAN = "plan"
    private const val K_FIRST = "first_launch"

    /** Compose 可观察状态 */
    val agreed = mutableStateOf(false)
    val plan = mutableStateOf(NONE)

    fun load(ctx: Context) {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        agreed.value = sp.getBoolean(K_AGREED, false)
        plan.value = sp.getInt(K_PLAN, NONE)
    }

    fun setAgreed(ctx: Context, v: Boolean) {
        agreed.value = v
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(K_AGREED, v).apply()
    }

    fun setPlan(ctx: Context, p: Int) {
        plan.value = p
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(K_PLAN, p).apply()
    }

    fun reset(ctx: Context) {
        agreed.value = false
        plan.value = NONE
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /** 当前是否允许使用 Root 功能 */
    fun canUseRoot(): Boolean = plan.value == ROOT

    fun planName(): String = when (plan.value) {
        BASIC -> "基础方案"
        ROOT -> "Root 方案"
        else -> "未选择"
    }
}
