import {
    App,
    applyDocumentTheme,
    applyHostFonts,
    applyHostStyleVariables,
    type McpUiHostContext,
} from "@modelcontextprotocol/ext-apps";
import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";
import "./global.css";
import "./app.css";

const mainEl = document.querySelector(".main") as HTMLElement;
const unlockEl = document.querySelector("#btn-unlock") as HTMLElement;
const optimizeEl = document.querySelector("#btn-optimize") as HTMLElement;
const purgeEl = document.querySelector("#btn-purge") as HTMLElement;
const statusUnlockEl = document.querySelector("#status-unlock") as HTMLElement;
const statusOptimizeEl = document.querySelector("#status-optimize") as HTMLElement;
const statusPurgeEl = document.querySelector("#status-purge") as HTMLElement;

function handleHostContextChanged(ctx: McpUiHostContext) {
    if (ctx.theme) {
        applyDocumentTheme(ctx.theme);
    }
    if (ctx.styles?.variables) {
        applyHostStyleVariables(ctx.styles.variables);
    }
    if (ctx.styles?.css?.fonts) {
        applyHostFonts(ctx.styles.css.fonts);
    }
    if (ctx.safeAreaInsets) {
        mainEl.style.paddingTop = `${ctx.safeAreaInsets.top}px`;
        mainEl.style.paddingRight = `${ctx.safeAreaInsets.right}px`;
        mainEl.style.paddingBottom = `${ctx.safeAreaInsets.bottom}px`;
        mainEl.style.paddingLeft = `${ctx.safeAreaInsets.left}px`;
    }
}

interface UnlockIndexResponse {
    success: boolean;
    message?: string;
    lockFileExisted?: boolean;
    lockFilePath?: string;
    error?: string;
}

interface OptimizeIndexResponse {
    success: boolean;
    operationId?: string;
    targetSegments?: number;
    currentSegments?: number;
    message?: string;
    error?: string;
}

interface PurgeIndexResponse {
    success: boolean;
    operationId?: string;
    documentsDeleted?: number;
    fullPurge?: boolean;
    message?: string;
    error?: string;
}

function showStatus(element: HTMLElement, type: 'success' | 'error' | 'info', title: string, details?: string) {
    element.className = 'status-message';
    element.classList.add(`status-message--${type}`);

    let html = `<div class="status-message__title">${title}</div>`;
    if (details) {
        html += `<div class="status-message__details">${details}</div>`;
    }

    element.innerHTML = html;
}

function parseJsonResponse<T>(result: CallToolResult): T | null {
    try {
        if (result.content && result.content.length > 0) {
            const content = result.content[0];
            if (content.type === 'text') {
                return JSON.parse(content.text) as T;
            }
        }
    } catch (e) {
        console.error('Failed to parse response:', e);
    }
    return null;
}

function updateStatusUnlock(result: CallToolResult) {
    const response = parseJsonResponse<UnlockIndexResponse>(result);

    if (!response) {
        showStatus(statusUnlockEl, 'error', 'Technical problem: Unable to parse server response');
        app.sendLog({ level: 'error', data: 'unlockIndex: Failed to parse server response' });
        return;
    }

    if (response.success) {
        const details = response.lockFileExisted
            ? `Lock file existed at: ${response.lockFilePath || 'unknown path'}`
            : 'No lock file was present';
        showStatus(statusUnlockEl, 'success', `Operation completed successfully`, details);
        app.sendLog({ level: 'info', data: `unlockIndex: Success - ${details}` });
    } else {
        showStatus(statusUnlockEl, 'error', `Operation failed`, response.error || 'Unknown error');
        app.sendLog({ level: 'error', data: `unlockIndex: Failed - ${response.error || 'Unknown error'}` });
    }
}

function updateStatusOptimize(result: CallToolResult) {
    const response = parseJsonResponse<OptimizeIndexResponse>(result);

    if (!response) {
        showStatus(statusOptimizeEl, 'error', 'Technical problem: Unable to parse server response');
        app.sendLog({ level: 'error', data: 'optimizeIndex: Failed to parse server response' });
        return;
    }

    if (response.success) {
        const details = `Operation ID: ${response.operationId}\n` +
            `Current segments: ${response.currentSegments || 'N/A'}\n` +
            `Target segments: ${response.targetSegments || 'N/A'}\n\n` +
            `Use getIndexAdminStatus tool to poll for progress.`;
        showStatus(statusOptimizeEl, 'info', `Optimization started`, details);
        app.sendLog({ level: 'info', data: `optimizeIndex: Started - Operation ID: ${response.operationId}, Current segments: ${response.currentSegments}, Target: ${response.targetSegments}` });
    } else {
        showStatus(statusOptimizeEl, 'error', `Operation failed`, response.error || 'Unknown error');
        app.sendLog({ level: 'error', data: `optimizeIndex: Failed - ${response.error || 'Unknown error'}` });
    }
}

function updateStatusPurge(result: CallToolResult) {
    const response = parseJsonResponse<PurgeIndexResponse>(result);

    if (!response) {
        showStatus(statusPurgeEl, 'error', 'Technical problem: Unable to parse server response');
        app.sendLog({ level: 'error', data: 'purgeIndex: Failed to parse server response' });
        return;
    }

    if (response.success) {
        const purgeType = response.fullPurge ? 'Full purge' : 'Soft purge';
        const details = `Operation ID: ${response.operationId}\n` +
            `Documents deleted: ${response.documentsDeleted || 0}\n` +
            `Purge type: ${purgeType}\n\n` +
            `Use getIndexAdminStatus tool to poll for progress.`;
        showStatus(statusPurgeEl, 'info', `Purge started`, details);
        app.sendLog({ level: 'info', data: `purgeIndex: Started - Operation ID: ${response.operationId}, Documents deleted: ${response.documentsDeleted}, Type: ${purgeType}` });
    } else {
        showStatus(statusPurgeEl, 'error', `Operation failed`, response.error || 'Unknown error');
        app.sendLog({ level: 'error', data: `purgeIndex: Failed - ${response.error || 'Unknown error'}` });
    }
}

// 1. Create app instance
const app = new App({ name: "Lucene Admin App", version: "1.0.0" });

// 2. Register handlers BEFORE connecting
app.onteardown = async () => {
    console.info("App is being torn down");
    return {};
};

app.ontoolinput = (params) => {
    console.info("Received tool call input:", params);
};

app.ontoolresult = (result) => {
    console.info("Received tool call result:", result);
};

app.ontoolcancelled = (params) => {
    console.info("Tool call cancelled:", params.reason);
};

app.onerror = console.error;

app.onhostcontextchanged = handleHostContextChanged;

unlockEl.addEventListener("click", async () => {
    try {
        statusUnlockEl.innerHTML = '';
        console.info("Calling unlockIndex tool...");
        const result = await app.callServerTool({ name: "unlockIndex", arguments: {"confirm": true} });
        updateStatusUnlock(result);
        console.info("unlockIndex result:", result);
    } catch (e) {
        console.error(e);
        const errorMessage = e instanceof Error ? e.message : 'An unexpected error occurred';
        showStatus(statusUnlockEl, 'error', 'Technical problem: ' + errorMessage);
        app.sendLog({ level: 'error', data: `unlockIndex: Exception - ${errorMessage}` });
    }
});

optimizeEl.addEventListener("click", async () => {
    try {
        statusOptimizeEl.innerHTML = '';
        console.info("Calling optimizeIndex tool...");
        const result = await app.callServerTool({ name: "optimizeIndex", arguments: {} });
        updateStatusOptimize(result);
        console.info("optimizeIndex result:", result);
    } catch (e) {
        console.error(e);
        const errorMessage = e instanceof Error ? e.message : 'An unexpected error occurred';
        showStatus(statusOptimizeEl, 'error', 'Technical problem: ' + errorMessage);
        app.sendLog({ level: 'error', data: `optimizeIndex: Exception - ${errorMessage}` });
    }
});

purgeEl.addEventListener("click", async () => {
    try {
        statusPurgeEl.innerHTML = '';
        console.info("Calling purgeIndex tool...");
        const result = await app.callServerTool({ name: "purgeIndex", arguments: {"confirm": true} });
        updateStatusPurge(result);
        console.info("purgeIndex result:", result);
    } catch (e) {
        console.error(e);
        const errorMessage = e instanceof Error ? e.message : 'An unexpected error occurred';
        showStatus(statusPurgeEl, 'error', 'Technical problem: ' + errorMessage);
        app.sendLog({ level: 'error', data: `purgeIndex: Exception - ${errorMessage}` });
    }
});

// 3. Connect to host
app.connect().then(() => {
    const ctx = app.getHostContext();
    if (ctx) {
        handleHostContextChanged(ctx);
    }
});
