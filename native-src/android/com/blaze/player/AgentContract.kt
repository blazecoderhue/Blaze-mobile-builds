// Blaze Player (Apache-2.0). Added by Blazeinchue.
package com.blaze.player

/** Public constants of the Blaze Player agent interface. Mirrored in AGENT_API.md. */
object AgentContract {
    const val PERMISSION = "com.blaze.player.permission.AGENT"
    const val API_VERSION = 1

    // Intent actions (AgentActivity)
    const val ACTION_OPEN = "com.blaze.player.action.OPEN"
    const val ACTION_PLAY = "com.blaze.player.action.PLAY"
    const val ACTION_CAST = "com.blaze.player.action.CAST"
    const val ACTION_QUEUE_ADD = "com.blaze.player.action.QUEUE_ADD"
    const val ACTION_QUEUE_LIST = "com.blaze.player.action.QUEUE_LIST"
    const val ACTION_QUEUE_CLEAR = "com.blaze.player.action.QUEUE_CLEAR"
    const val ACTION_SEARCH = "com.blaze.player.action.SEARCH"

    // Bound service
    const val ACTION_BIND = "com.blaze.player.agent.BIND"
    const val MSG_PING = 0
    const val MSG_OPEN = 1
    const val MSG_PLAY = 2
    const val MSG_CAST = 3
    const val MSG_QUEUE_ADD = 4
    const val MSG_QUEUE_LIST = 5
    const val MSG_QUEUE_CLEAR = 6
    const val MSG_SEARCH = 7

    // Extras / Bundle keys
    const val EXTRA_URI = "uri"
    const val EXTRA_SOURCE_ID = "sourceId"
    const val EXTRA_TITLE = "title"
    const val EXTRA_SCREEN = "screen"
    const val EXTRA_QUERY = "query"
    const val EXTRA_SOURCES = "sources"
    const val EXTRA_LIMIT = "limit"
    const val EXTRA_RESULT_JSON = "json"
    const val EXTRA_ERROR = "error"
    const val EXTRA_ERROR_CODE = "errorCode"

    // Reply arg1
    const val OK = 0
    const val ERR = 1
}
