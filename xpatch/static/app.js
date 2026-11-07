"use strict";
const $ = id => document.getElementById(id);
const token = document.querySelector('meta[name="xpatch-token"]').content;
let state, draft, dirty = false, busy = false, uploading = false, catalogKey = "";

function notice(message = "") { $("notice").textContent = message; $("notice").hidden = !message; }
async function api(path, body) {
  const response = await fetch(`/api/${path}`, {
    method: body === undefined ? "GET" : "POST",
    headers: {"X-XPatch-Token": token, "Content-Type": "application/json"},
    ...(body === undefined ? {} : {body: JSON.stringify(body)})
  });
  const result = await response.json();
  if (!response.ok) throw new Error(result.error || "Request failed");
  return result;
}
async function action(fn) { try { notice(); await fn(); await refresh(); } catch (error) { notice(error.message); } }
function node(tag, text, className) {
  const element = document.createElement(tag);
  if (text !== undefined) element.textContent = text;
  if (className) element.className = className;
  return element;
}
function modified() { dirty = true; updateProfileState(); }
function updateProfileState() {
  $("count").textContent = `${draft.patches.length} selected`;
  $("saved").textContent = dirty ? "Unsaved changes" : "Profile saved. Ready for the next supported version.";
  $("save").disabled = busy || !state.catalog.length || !dirty;
  document.querySelectorAll(".preset").forEach(button => {
    const names = state.presets[button.textContent];
    button.classList.toggle("active", names.length === draft.patches.length && names.every(n => draft.patches.includes(n)));
    button.disabled = busy || !state.catalog.length;
  });
  $("patch").disabled = busy || uploading || !$("input").value || !state.catalog.length;
}
const barPattern = /\b(?:bars?|tabs?|FAB)\b/i;
const clutterPattern = /ads|upsell|hide|recommend|promot|nudge/i;
const mediaPattern = /download|video|PiP|Undo|font|color|share/i;
function renderPatches() {
  if (!state || !draft) return;
  const container = $("patches"); container.replaceChildren();
  const search = $("search").value.toLowerCase(), category = $("category").value;
  const missing = draft.patches.filter(name => !state.catalog.some(p => p.name === name));
  for (const name of missing) {
    const row = node("div", undefined, "patch-item");
    row.append(node("span", `Unavailable in this release: ${name}. Remove it or wait for support.`, "patch-description"));
    const remove = node("button", "Remove from profile", "text-button");
    remove.disabled = busy;
    remove.onclick = () => { draft.patches = draft.patches.filter(n => n !== name); delete draft.options[name]; modified(); renderPatches(); };
    row.append(remove); container.append(row);
  }
  const patches = state.catalog.filter(p => `${p.name} ${p.description || ""}`.toLowerCase().includes(search))
    .filter(p => category === "all" || (category === "bars" ? barPattern : category === "ads" ? clutterPattern : mediaPattern).test(p.name));
  for (const patch of patches) {
    const row = node("div", undefined, "patch-item"), label = node("label", undefined, "patch-label");
    const check = node("input"); check.type = "checkbox"; check.checked = draft.patches.includes(patch.name); check.disabled = busy;
    const text = node("span"); text.append(node("span", patch.name, "patch-title"));
    text.append(node("span", patch.description || (barPattern.test(patch.name) ? "Configure visible items in the patched app’s Piko settings." : "Enable this Piko module in your patched client."), "patch-description"));
    label.append(check, text); row.append(label);
    check.onchange = () => {
      if (check.checked) draft.patches.push(patch.name);
      else { draft.patches = draft.patches.filter(n => n !== patch.name); delete draft.options[patch.name]; }
      modified(); renderPatches();
    };
    if (check.checked) for (const option of patch.options || []) {
      const field = node("label", option.title || option.key, "option");
      const value = draft.options[patch.name]?.[option.key] ?? option.default;
      const input = node("input"); input.disabled = busy;
      if (option.type === "kotlin.Boolean") { input.type = "checkbox"; input.checked = value; }
      else { input.type = option.type === "kotlin.Int" ? "number" : "text"; input.value = Array.isArray(value) ? JSON.stringify(value) : value ?? ""; }
      input.onchange = () => {
        try {
          let result = input.type === "checkbox" ? input.checked : input.type === "number" ? Number(input.value) : input.value;
          if (option.type.startsWith("kotlin.collections.List")) {
            result = JSON.parse(input.value);
            if (!Array.isArray(result) || result.some(item => typeof item !== "string")) throw new Error();
          }
          input.setCustomValidity("");
          draft.options[patch.name] ||= {}; draft.options[patch.name][option.key] = result; modified(); notice();
        } catch (_) { input.setCustomValidity("Enter a JSON list of strings."); input.reportValidity(); notice(`Enter a valid JSON list for ${option.title || option.key}.`); }
      };
      field.append(input); if (option.description) field.append(node("span", option.description, "patch-description")); row.append(field);
    }
    container.append(row);
  }
  if (!patches.length && !missing.length) container.append(node("p", state.catalog.length ? "No matching patches." : "Check for patch updates to load available mods.", "empty"));
}
function renderPresets() {
  $("presets").replaceChildren();
  for (const [name, patches] of Object.entries(state.presets)) {
    const button = node("button", name, "preset");
    button.onclick = () => { draft = {patches: [...patches], options: {}}; modified(); renderPatches(); };
    $("presets").append(button);
  }
}
async function refresh() {
  try {
    const previousBusy = busy;
    state = await api("state"); busy = state.job.state === "running";
    $("connection").textContent = "Running locally"; $("connection").dataset.state = "succeeded";
    if (!draft) { draft = structuredClone(state.profile); renderPresets(); }
    const key = JSON.stringify([state.release.patches?.tag, state.release.engine?.tag]);
    if (key !== catalogKey || busy !== previousBusy) { catalogKey = key; renderPatches(); }
    $("supported").textContent = state.versions.join(", ") || "Sync to check";
    $("release").textContent = state.release.patches ? `Piko ${state.release.patches.tag} / Morphe ${state.release.engine.tag}` : "Powered by Piko + Morphe";
    $("sync").disabled = busy;
    $("watch").checked = state.settings.watch; $("auto-sync").checked = state.settings.autoSync;
    $("data-directory").textContent = state.dataDirectory;
    const selected = $("input").value;
    const listKey = JSON.stringify(state.inputs);
    if ($("input").dataset.items !== listKey) {
      $("input").replaceChildren();
      if (!state.inputs.length) $("input").append(new Option("No APKs yet", ""));
      for (const input of state.inputs) $("input").append(new Option(`${input.name} (${(input.size / 1048576).toFixed(1)} MB)`, input.name));
      if (state.inputs.some(i => i.name === selected)) $("input").value = selected;
      $("input").dataset.items = listKey;
    }
    $("input").disabled = busy;
    $("job-state").textContent = state.job.state[0].toUpperCase() + state.job.state.slice(1);
    $("job-state").dataset.state = state.job.state;
    $("cancel").hidden = !busy;
    const logs = $("logs"), nearBottom = logs.scrollHeight - logs.scrollTop - logs.clientHeight < 50;
    const content = state.job.logs.join("\n") || "Your build log will appear here.";
    if (logs.textContent !== content) { logs.textContent = content; if (nearBottom) logs.scrollTop = logs.scrollHeight; }
    $("outputs").replaceChildren();
    for (const output of state.outputs) {
      const row = node("div", undefined, "output");
      row.append(node("span", output.name), node("small", `${(output.size / 1048576).toFixed(1)} MB`));
      const link = node("a", "Download APK"); link.href = `/download/${encodeURIComponent(output.name)}?token=${encodeURIComponent(token)}`; link.download = output.name;
      row.append(link); $("outputs").append(row);
    }
    updateProfileState();
  } catch (error) { $("connection").textContent = "Disconnected"; $("connection").dataset.state = "failed"; notice(error.message); }
}
async function save() {
  for (const input of document.querySelectorAll(".option input")) if (!input.reportValidity()) throw new Error("Correct the invalid patch option before saving.");
  await api("profile", draft); dirty = false;
}
$("sync").onclick = () => action(() => api("sync", {}));
$("save").onclick = () => action(save);
$("patch").onclick = () => action(async () => { if (dirty) await save(); await api("patch", {name: $("input").value}); });
$("cancel").onclick = () => action(() => api("cancel", {}));
$("input").onchange = () => updateProfileState();
$("search").oninput = renderPatches; $("category").onchange = renderPatches;
$("watch").onchange = () => action(async () => { if ($("watch").checked && dirty) await save(); await api("settings", {watch: $("watch").checked}); });
$("auto-sync").onchange = () => action(() => api("settings", {autoSync: $("auto-sync").checked}));
async function upload(file) {
  if (!file || uploading) return;
  if (!/\.(apk|apkm|apks|xapk)$/i.test(file.name) || file.size > 1073741824 || file.size === 0) return notice("Choose an APK bundle up to 1 GiB.");
  uploading = true; notice(); $("upload-progress").hidden = false; $("upload-label").textContent = "Uploading…"; updateProfileState();
  try {
    await new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest(); xhr.open("POST", `/api/upload?name=${encodeURIComponent(file.name)}`);
      xhr.setRequestHeader("X-XPatch-Token", token);
      xhr.upload.onprogress = event => { if (event.lengthComputable) $("upload-progress").value = event.loaded / event.total * 100; };
      xhr.onload = () => { try { const data = JSON.parse(xhr.responseText); xhr.status === 200 ? resolve(data) : reject(new Error(data.error)); } catch (_) { reject(new Error("Upload failed")); } };
      xhr.onerror = () => reject(new Error("Upload failed; check the local server.")); xhr.send(file);
    });
    await refresh(); $("input").value = file.name;
  } catch (error) { notice(error.message); }
  finally { uploading = false; $("upload-progress").hidden = true; $("upload-label").textContent = "Choose or drop an APK"; $("upload").value = ""; updateProfileState(); }
}
$("upload").onchange = () => upload($("upload").files[0]);
$("drop").ondragover = event => { event.preventDefault(); $("drop").classList.add("drag"); };
$("drop").ondragleave = () => $("drop").classList.remove("drag");
$("drop").ondrop = event => { event.preventDefault(); $("drop").classList.remove("drag"); upload(event.dataTransfer.files[0]); };
$("export").onclick = () => {
  const url = URL.createObjectURL(new Blob([JSON.stringify(draft, null, 2)], {type: "application/json"}));
  const link = node("a"); link.href = url; link.download = "xpatch-profile.json"; link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000);
};
$("import").onchange = () => action(async () => {
  const file = $("import").files[0]; if (!file) return;
  if (file.size > 1048576) throw new Error("Profile exceeds size limit");
  const profile = JSON.parse(await file.text()); await api("profile", profile);
  draft = profile; draft.options ||= {}; dirty = false; renderPatches(); $("import").value = "";
});
refresh(); setInterval(refresh, 1500);
