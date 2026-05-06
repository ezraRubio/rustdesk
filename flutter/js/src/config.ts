const _ = localStorage;

export function parseIniConfig(text: string): Record<string, Record<string, string>> {
  const result: Record<string, Record<string, string>> = {};
  let currentSection = "";
  const lines = text.split("\n");

  for (let line of lines) {
    line = line.trim();
    if (!line || line.startsWith("#") || line.startsWith(";")) continue;

    if (line.startsWith("[") && line.endsWith("]")) {
      currentSection = line.substring(1, line.length - 1).trim();
      result[currentSection] = {};
      continue;
    }

    const eqIdx = line.indexOf("=");
    if (eqIdx > 0) {
      const key = line.substring(0, eqIdx).trim();
      const value = line.substring(eqIdx + 1).trim();
      if (currentSection) {
        result[currentSection][key] = value;
      } else {
        if (!result[""]) result[""] = {};
        result[""][key] = value;
      }
    }
  }

  return result;
}

export async function loadCustomConfig() {
  try {
    const el = document.getElementById("custom-config");
    if (!el) {
      console.log("custom-config script tag not found in HTML");
      return;
    }

    let text = el.textContent || (el as any).innerText || "";
    text = text.trim();
    if (!text) return;

    try {
      text = atob(text);
    } catch {}

    const config = parseIniConfig(text);
    console.log("Loaded custom config:", config);

    if (config[""] && config[""]["app-name"]) {
      _.setItem("app-name", config[""]["app-name"]);
    }

    if (config["default-settings"]) {
      for (const [key, value] of Object.entries(config["default-settings"])) {
        const normalized = key.replace(/_/g, "-");
        _.setItem(`default:${normalized}`, value);
        const underscored = normalized.replace(/-/g, "_");
        _.setItem(`default:${underscored}`, value);
      }
    }

    if (config["override-settings"]) {
      for (const [key, value] of Object.entries(config["override-settings"])) {
        const normalized = key.replace(/_/g, "-");
        _.setItem(`override:${normalized}`, value);
        const underscored = normalized.replace(/-/g, "_");
        _.setItem(`override:${underscored}`, value);
      }
    }
  } catch (e) {
    console.error("Failed to load custom config:", e);
  }
}

export function getEffectiveOption(key: string): string {
  const override = _.getItem(`override:${key}`);
  if (override !== null) return override;

  const userSet = _.getItem(key);
  if (userSet !== null) return userSet;

  const defaultVal = _.getItem(`default:${key}`);
  if (defaultVal !== null) return defaultVal;

  return "";
}
