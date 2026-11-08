import type { ReactNode } from "react";

export type IconName =
    | "plus"
    | "collapse"
    | "expand"
    | "chat"
    | "history"
    | "trash"
    | "close"
    | "document"
    | "upload"
    | "summary"
    | "search"
    | "question"
    | "send";

interface IconProps {
    name: IconName;
    size?: number;
    className?: string;
}

function Icon({ name, size = 20, className }: IconProps) {
    const iconPaths: Record<IconName, ReactNode> = {
        plus: <path d="M12 5v14M5 12h14" />,
        collapse: <path d="m14 6-6 6 6 6" />,
        expand: <path d="m10 6 6 6-6 6" />,
        chat: (
            <>
                <path d="M20 11.5a7.5 7.5 0 0 1-7.5 7.5H5l1.8-3A7.5 7.5 0 1 1 20 11.5Z" />
                <path d="M8.5 11.5h.01M12.5 11.5h.01M16.5 11.5h.01" />
            </>
        ),
        history: (
            <>
                <path d="M3 12a9 9 0 1 0 2.6-6.4L3 8" />
                <path d="M3 3v5h5M12 7v5l3 2" />
            </>
        ),
        trash: (
            <>
                <path d="M4 7h16M10 11v6M14 11v6M5.5 7l1 13h11l1-13M9 7V4h6v3" />
            </>
        ),
        close: <path d="m6 6 12 12M18 6 6 18" />,
        document: (
            <>
                <path d="M13 3H6a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V10Z" />
                <path d="M13 3v7h7M8 14h8M8 17h8" />
            </>
        ),
        upload: (
            <>
                <path d="M12 16V4m0 0L7 9m5-5 5 5" />
                <path d="M5 14v5a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-5" />
            </>
        ),
        summary: (
            <>
                <path d="M5 4h14a1 1 0 0 1 1 1v14a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V5a1 1 0 0 1 1-1Z" />
                <path d="M8 8h8M8 12h8M8 16h5" />
            </>
        ),
        search: (
            <>
                <circle cx="10.8" cy="10.8" r="6.8" />
                <path d="m16 16 4.5 4.5" />
            </>
        ),
        question: (
            <>
                <circle cx="12" cy="12" r="9" />
                <path d="M9.8 9a2.3 2.3 0 1 1 3.8 1.7c-1 .8-1.6 1.2-1.6 2.8M12 17h.01" />
            </>
        ),
        send: <path d="M5 12h14m-6-6 6 6-6 6" />,
    };

    return (
        <svg
            aria-hidden="true"
            className={className}
            width={size}
            height={size}
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.8"
            strokeLinecap="round"
            strokeLinejoin="round"
        >
            {iconPaths[name]}
        </svg>
    );
}

export default Icon;
