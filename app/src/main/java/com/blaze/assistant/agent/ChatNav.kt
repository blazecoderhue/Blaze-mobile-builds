package com.blaze.assistant.agent

/**
 * v0.28.3: where the user is in the shell (Chat | Projects), as one small pure value so the rules are unit tested.
 *
 * Rules (Luke's phone report: opening a project chat replaced the One Mind main chat):
 *  - The Chat tab always shows the one main conversation ([Mind.MAIN]). Nothing here ever changes that id.
 *  - Project chats open inside Projects: projects list → project page → project chat. Back walks that stack.
 *  - Switching the pill to Chat and back to Projects restores where you were in Projects.
 *  - When something outside the UI shows a project chat (a notification, the secure form card, a reply from a
 *    notification), [follow] moves the user into Projects at that chat instead of loading it into main Chat.
 */
data class ChatNav(
    /** [CHAT], [PROJECTS] or (v0.29.0) [HOME]. */
    val tab: String = CHAT,
    /** The open project on the Projects tab ("" = the projects list). */
    val project: String = "",
    /** The open project chat ("" = the project page). Only meaningful with a [project]. */
    val chat: String = "",
) {
    /** True while a project chat screen is on screen. */
    val inProjectChat: Boolean get() = tab == PROJECTS && project.isNotBlank() && chat.isNotBlank()

    /** The thread the agent should have on screen: the open project chat, else always the main conversation. */
    val visibleChat: String get() = if (inProjectChat) chat else MAIN

    /** The thread the Chat pill shows. A constant: project chats never replace it. */
    val mainChat: String get() = MAIN

    /** Chat | Projects pill. Keeps the Projects position so coming back restores it. */
    fun switchTab(to: String): ChatNav = copy(tab = tabOf(to))

    /** The projects list (e.g. /projects). */
    fun projectsList(): ChatNav = ChatNav(PROJECTS, "", "")

    fun openProject(id: String): ChatNav = ChatNav(PROJECTS, id, "")

    fun openChat(projectId: String, chatId: String): ChatNav = ChatNav(PROJECTS, projectId, chatId)

    /** One step back inside Projects (chat → project page → list), or null when back should go to the system. */
    fun back(): ChatNav? = when {
        tab != PROJECTS -> null
        chat.isNotBlank() -> copy(chat = "")
        project.isNotBlank() -> copy(project = "")
        else -> null
    }

    /** A project was deleted: leave it if it's open. */
    fun projectDeleted(id: String): ChatNav = if (project == id) copy(project = "", chat = "") else this

    /** A chat was deleted: back to its project page if it's open. */
    fun chatDeleted(id: String): ChatNav = if (chat == id) copy(chat = "") else this

    /**
     * The agent put [shown] on screen. Keeps the UI in step: a project chat opens in Projects, a private chat lives on
     * the Chat tab, the main thread showing while a project chat screen is open (e.g. it was deleted) goes back to the
     * project page. Anything else (an archived or background-agent chat) shows on the Chat tab, as before.
     */
    fun follow(shown: String, projectOf: (String) -> String?): ChatNav {
        if (shown.isBlank() || shown == visibleChat) return this
        if (PrivateChat.isPrivateId(shown)) return copy(tab = CHAT)
        val p = projectOf(shown)
        if (p != null) return openChat(p, shown)
        if (shown == MAIN) return if (inProjectChat) copy(chat = "") else this
        return copy(tab = CHAT)
    }

    /** For rememberSaveable (a plain string survives rotation and process death). */
    fun encode(): String = listOf(tab, project, chat).joinToString(SEP)

    companion object {
        const val CHAT = "chat"
        const val PROJECTS = "projects"
        /** v0.29.0: the Home board. */
        const val HOME = "home"

        fun tabOf(s: String?): String = when (s) { PROJECTS -> PROJECTS; HOME -> HOME; else -> CHAT }

        /** The tab to open on start: Home when the user last left it there (remembered), else Chat (the default on first run). */
        fun startTab(remembered: String?): String = if (remembered == HOME) HOME else CHAT
        const val MAIN: String = Mind.MAIN
        private const val SEP = "\u001f"

        fun decode(s: String?): ChatNav {
            val p = s.orEmpty().split(SEP)
            if (p.size != 3) return ChatNav()
            val tab = tabOf(p[0])
            val project = p[1]
            return ChatNav(tab, project, if (project.isBlank()) "" else p[2])
        }

    }
}
