import {
    App,
    applyDocumentTheme,
    applyHostFonts,
    applyHostStyleVariables,
    type McpUiHostContext,
} from "@modelcontextprotocol/ext-apps";
//import type { CallToolResult } from "@modelcontextprotocol/sdk/types.js";
import "./global.css";
import "./app.css";

const mainEl = document.querySelector(".main") as HTMLElement;
const unlockEl = document.querySelector("#btn-unlock") as HTMLElement;
const optimizeEl = document.querySelector("#btn-optimize") as HTMLElement;
const purgeEl = document.querySelector("#btn-purge") as HTMLElement;

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
    //serverTimeEl.textContent = extractTime(result);
};

app.ontoolcancelled = (params) => {
    console.info("Tool call cancelled:", params.reason);
};

app.onerror = console.error;

app.onhostcontextchanged = handleHostContextChanged;

unlockEl.addEventListener("click", async () => {
    try {
        console.info("Calling unlockIndex tool...");
        const result = await app.callServerTool({ name: "unlockIndex", arguments: {"confirm": true} });
        console.info("unlockIndex result:", result);

        //serverTimeEl.textContent = extractTime(result);
    } catch (e) {
        console.error(e);
        //serverTimeEl.textContent = "[ERROR]";
    }
});

optimizeEl.addEventListener("click", async () => {
    try {
        console.info("Calling optimizeIndex tool...");
        const result = await app.callServerTool({ name: "optimizeIndex", arguments: {} });
        console.info("optimizeIndex result:", result);

        //serverTimeEl.textContent = extractTime(result);
    } catch (e) {
        console.error(e);
        //serverTimeEl.textContent = "[ERROR]";
    }
});

purgeEl.addEventListener("click", async () => {
    try {
        console.info("Calling purgeIndex tool...");
        const result = await app.callServerTool({ name: "purgeIndex", arguments: {"confirm": true} });
        console.info("purgeIndex result:", result);

        //serverTimeEl.textContent = extractTime(result);
    } catch (e) {
        console.error(e);
        //serverTimeEl.textContent = "[ERROR]";
    }
});

// 3. Connect to host
app.connect().then(() => {
    const ctx = app.getHostContext();
    if (ctx) {
        handleHostContextChanged(ctx);
    }
});
