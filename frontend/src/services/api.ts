const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080";
const API_CREDENTIALS: RequestCredentials =
    import.meta.env.VITE_API_CREDENTIALS === "include"
        ? "include"
        : "same-origin";

interface ApiRequestOptions {
    method?: "GET" | "POST" | "PUT" | "DELETE";
    body?: BodyInit | null;
    headers?: HeadersInit;
}

async function request<T>(
    endpoint: string,
    options: ApiRequestOptions = {}
): Promise<T> {
    const response = await fetch(
        `${API_BASE_URL}${endpoint}`,
        {
            method: options.method ?? "GET",
            headers: options.headers,
            body: options.body,
            credentials: API_CREDENTIALS,
        }
    );

    if (!response.ok) {
        const errorMessage = await response.text();

        throw new Error(
            errorMessage ||
            `API request failed with status ${response.status}`
        );
    }

    return await response.json() as Promise<T>;
}

export const api = {
    get<T>(endpoint: string): Promise<T> {
        return request<T>(endpoint);
    },

    post<T>(
        endpoint: string,
        body: unknown
    ): Promise<T> {
        return request<T>(endpoint, {
            method: "POST",
            headers: {
                "Content-Type": "application/json",
            },
            body: JSON.stringify(body),
        });
    },

    postForm<T>(
        endpoint: string,
        formData: FormData
    ): Promise<T> {
        return request<T>(endpoint, {
            method: "POST",
            body: formData,
        });
    },

    delete<T>(endpoint: string): Promise<T> {
        return request<T>(endpoint, {
            method: "DELETE",
        });
    },
};

export { API_BASE_URL, API_CREDENTIALS };