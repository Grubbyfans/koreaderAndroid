package com.yuezhou.reader.web

/**
 * 网页页面状态机（阶段 C）：用导航代次隔离旧回调，避免旧页面覆盖新状态。
 *
 * 状态仅描述容器可见性，不抽取或推断页面书目；503/验证页按普通网页继续展示。
 */
enum class WebPageState {
    IDLE,
    LOADING,
    VERIFICATION_OR_WAITING,
    CONTENT_VISIBLE,
    NETWORK_ERROR,
    TIMEOUT,
    RENDERER_GONE,
}

class WebPageStateMachine {

    var state: WebPageState = WebPageState.IDLE
        private set

    var generation: Int = 0
        private set

    /** 主框架开始导航，旧代次回调从此失效。 */
    fun beginNavigation(): Int {
        generation += 1
        state = WebPageState.LOADING
        return generation
    }

    /** 服务端返回 503/验证页：页面仍可见，不阻塞，也不自行处理验证。 */
    fun markWaiting(generation: Int): Boolean {
        if (!accept(generation) || state != WebPageState.LOADING) return false
        state = WebPageState.VERIFICATION_OR_WAITING
        return true
    }

    /** 页面真实内容可见：撤去阻塞层（不依据书卡数量判断）。 */
    fun markContentVisible(generation: Int): Boolean {
        if (!accept(generation)) return false
        // 网络错误需用户显式重试；超时后内容最终到达则恢复正常展示。
        if (state == WebPageState.NETWORK_ERROR) return false
        state = WebPageState.CONTENT_VISIBLE
        return true
    }

    fun markNetworkError(generation: Int): Boolean {
        if (!accept(generation)) return false
        state = WebPageState.NETWORK_ERROR
        return true
    }

    /** 超时只对仍在加载/等待的代次生效，已可见页面不会被超时覆盖。 */
    fun markTimeout(generation: Int): Boolean {
        if (!accept(generation)) return false
        if (state != WebPageState.LOADING && state != WebPageState.VERIFICATION_OR_WAITING) return false
        state = WebPageState.TIMEOUT
        return true
    }

    /** 用户选择继续等待：回到加载态，不产生新代次，也不重新提交导航。 */
    fun continueWaiting() {
        if (state == WebPageState.TIMEOUT) state = WebPageState.LOADING
    }

    fun markRendererGone() {
        generation += 1
        state = WebPageState.RENDERER_GONE
    }

    /** 用户主动重建 WebView 后再由新的导航设置状态。 */
    fun resetAfterRendererGone() {
        if (state == WebPageState.RENDERER_GONE) state = WebPageState.IDLE
    }

    val blocking: Boolean
        get() = state == WebPageState.LOADING

    private fun accept(generation: Int): Boolean =
        generation == this.generation && state != WebPageState.RENDERER_GONE
}
