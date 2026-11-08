import type { SavedConversation } from "../services/conversation-storage.service";
import Icon from "./Icon";

interface SidebarProps {
    onNewChat: () => void;
    activeView: "chat" | "documents";
    onSelectView: (view: "chat" | "documents") => void;
    conversations: SavedConversation[];
    activeConversationId: string | null;
    onSelectConversation: (id: string) => void;
    onDeleteConversation: (id: string) => void;
    collapsed: boolean;
    onToggleCollapsed: () => void;
}

function Sidebar({
    onNewChat,
    activeView,
    onSelectView,
    conversations,
    activeConversationId,
    onSelectConversation,
    onDeleteConversation,
    collapsed,
    onToggleCollapsed,
}: SidebarProps) {
    return (
        <aside className="sidebar" aria-label="Workspace navigation">
            <div className="logo">
                <div className="logo-icon" aria-hidden="true">
                    D
                </div>
                <div>
                    <h2>DocuMind AI</h2>
                    <span>Your document workspace</span>
                </div>
                <button
                    className="sidebar-toggle"
                    type="button"
                    onClick={onToggleCollapsed}
                    aria-label={collapsed ? "Expand sidebar" : "Collapse sidebar"}
                    title={collapsed ? "Expand sidebar" : "Collapse sidebar"}
                >
                    <Icon name={collapsed ? "expand" : "collapse"} size={16} />
                </button>
            </div>

            <button className="new-chat" type="button" onClick={onNewChat}>
                <span className="new-chat-icon" aria-hidden="true">
                    <Icon name="plus" size={17} />
                </span>
                <span className="new-chat-label">New conversation</span>
            </button>

            <nav className="sidebar-section" aria-label="Workspace">
                <p className="section-title">Workspace</p>
                <button
                    className={`sidebar-item${activeView === "chat" ? " active" : ""}`}
                    type="button"
                    onClick={() => onSelectView("chat")}
                    aria-current={activeView === "chat" ? "page" : undefined}
                >
                    <span className="sidebar-item-icon" aria-hidden="true">
                        <Icon name="chat" size={18} />
                    </span>
                    <span>Chat workspace</span>
                </button>
                <button
                    className={`sidebar-item${activeView === "documents" ? " active" : ""}`}
                    type="button"
                    onClick={() => onSelectView("documents")}
                    aria-current={activeView === "documents" ? "page" : undefined}
                >
                    <span className="sidebar-item-icon" aria-hidden="true">
                        <Icon name="document" size={18} />
                    </span>
                    <span>Documents</span>
                </button>
            </nav>

            {conversations.length > 0 && (
                <nav className="recent-section" aria-label="Recent conversations">
                    <p className="section-title">Recent</p>
                    <div className="recent-list">
                        {conversations.map((conversation) => {
                            const isActive = conversation.id === activeConversationId;

                            return (
                                <div className="recent-row" key={conversation.id}>
                                    <button
                                        className={`recent-conversation${isActive ? " active" : ""}`}
                                        type="button"
                                        onClick={() => onSelectConversation(conversation.id)}
                                        title={conversation.title}
                                        aria-current={isActive ? "true" : undefined}
                                    >
                                        <span className="recent-icon" aria-hidden="true">
                                            <Icon name="history" size={15} />
                                        </span>
                                        <span>{conversation.title}</span>
                                    </button>
                                    <button
                                        className="delete-conversation"
                                        type="button"
                                        onClick={() => onDeleteConversation(conversation.id)}
                                        aria-label={`Delete conversation: ${conversation.title}`}
                                        title="Delete conversation"
                                    >
                                        <Icon name="close" size={15} />
                                    </button>
                                </div>
                            );
                        })}
                    </div>
                </nav>
            )}

            <div className="sidebar-bottom">
                <div className="sidebar-note">
                    <strong>A clearer way to read</strong>
                    <p>Answers are grounded in your documents, with sources included.</p>
                </div>
            </div>
        </aside>
    );
}

export default Sidebar;
