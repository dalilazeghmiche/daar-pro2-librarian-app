import re, sys, matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

src, out = sys.argv[1], sys.argv[2]
txt = open(src, encoding="utf-8").read()
plt.rcParams.update({"font.size": 10, "axes.spines.top": False, "axes.spines.right": False,
                     "axes.grid": True, "grid.alpha": .3, "figure.dpi": 150})
C = {"motcle": "#3b6ea5", "contient": "#c9a227", "regex_index": "#3b8a5a", "regex_texte": "#a33b2b",
     "index": "#3b6ea5", "jaccard": "#a33b2b", "clo": "#3b8a5a", "bet": "#c9a227", "pr": "#7a5aa5"}
num = r"([\d.]+) ms \+-\s+([\d.]+)"

blocs = re.split(r"===== (\d+) livres =====", txt)
tailles, req, pre = [], {}, {}
for i in range(1, len(blocs), 2):
    n, b = int(blocs[i]), blocs[i + 1].split("Moteurs RegEx")[0]
    tailles.append(n)
    m = re.search(r"index inverse\s+" + num, b); pre.setdefault("index", []).append(tuple(map(float, m.groups())))
    m = re.search(r"Jaccard \(\d+ paires\)\s+" + num, b); pre.setdefault("jaccard", []).append(tuple(map(float, m.groups())))
    for k, nom in [("clo", "closeness"), ("bet", "betweenness"), ("pr", "pagerank")]:
        m = re.search(nom + r"\s+" + num, b); pre.setdefault(k, []).append(tuple(map(float, m.groups())))
    for m in re.finditer(r"^\s+(motcle|contient|regex_index|regex_texte)\s+(\S+)\s+" + num + r"\s+(\d+) livres", b, re.M):
        req.setdefault((m.group(1), m.group(2)), []).append((float(m.group(3)), float(m.group(4)), int(m.group(5))))

def eb(ax, x, serie, **kw):
    ax.errorbar(x, [s[0] for s in serie], yerr=[s[1] for s in serie], marker="o", capsize=3, lw=1.8, ms=4, **kw)

# 1. Requetes vs taille (une requete representative par type), echelle log
fig, ax = plt.subplots(figsize=(7, 4.8))
for (t, q), lab in [(("motcle", "whale"), "Mot-clé « whale » (index inversé)"),
                    (("contient", "babylon"), "Contient « babylon » (KMP sur le vocabulaire)"),
                    (("regex_index", "s(a|g|r)+on"), "RegEx S(a|g|r)+on sur l'index (DFA minimal)"),
                    (("regex_texte", "S(a|g|r)+on"), "RegEx S(a|g|r)+on sur le texte complet")]:
    eb(ax, tailles, req[(t, q)], label=lab, color=C[t])
ax.set_yscale("log"); ax.set_xlabel("Nombre de livres dans la bibliothèque"); ax.set_ylabel("Temps de réponse (ms, échelle log)")
ax.set_title("Temps d'une requête selon la taille de la bibliothèque\n(moyenne ± écart-type, 10 mesures ; 3 pour le texte complet)")
ax.legend(fontsize=8, loc="upper center", bbox_to_anchor=(.5, -.18), ncol=2, frameon=False); fig.tight_layout(); fig.savefig(f"{out}/1_requetes_taille.png"); plt.close(fig)

# 2. Precalcul vs taille
fig, ax = plt.subplots(figsize=(7, 4.2))
eb(ax, tailles, [(a / 1000, b / 1000) for a, b in pre["jaccard"]], label="Jaccard, toutes les paires — O(n²)", color=C["jaccard"])
eb(ax, tailles, [(a / 1000, b / 1000) for a, b in pre["index"]], label="Construction de l'index inversé — O(taille totale)", color=C["index"])
n1, j1 = tailles[-1], pre["jaccard"][-1][0] / 1000
xs = list(range(0, n1 + 1, 20))
ax.plot(xs, [j1 * (x / n1) ** 2 for x in xs], ls="--", color=C["jaccard"], alpha=.5, lw=1, label="courbe en n² (référence)")
ax.set_xlabel("Nombre de livres"); ax.set_ylabel("Temps (s)")
ax.set_title("Précalcul hors ligne selon la taille de la bibliothèque\n(moyenne ± écart-type, 3 mesures, 8 cœurs)")
ax.legend(fontsize=8); fig.tight_layout(); fig.savefig(f"{out}/2_precalcul_taille.png"); plt.close(fig)

# 3. Centralite vs taille
fig, ax = plt.subplots(figsize=(7, 4.2))
for k, lab in [("bet", "Betweenness (Brandes) — O(n·m)"), ("clo", "Closeness (BFS depuis chaque sommet)"), ("pr", "PageRank (itération de la puissance)")]:
    eb(ax, tailles, pre[k], label=lab, color=C[k])
ax.set_xlabel("Nombre de livres"); ax.set_ylabel("Temps (ms)")
ax.set_title("Calcul des indices de centralité sur le graphe de Jaccard (θ = 0,70)\n(moyenne ± écart-type, 3 mesures)")
ax.legend(fontsize=8); fig.tight_layout(); fig.savefig(f"{out}/3_centralite_taille.png"); plt.close(fig)

# 4. Moteurs RegEx (barres)
mot = txt.split("Moteurs RegEx")[1]
res = {}
for m in re.finditer(r"^\s+(\S+)\s+(DFA minimal|simulation NFA)\s+" + num, mot, re.M):
    res.setdefault(m.group(1), {})[m.group(2)] = (float(m.group(3)), float(m.group(4)))
regs = list(res)
fig, ax = plt.subplots(figsize=(7, 4.2))
w = .38
for k, (moteur, col) in enumerate([("DFA minimal", "#3b8a5a"), ("simulation NFA", "#a33b2b")]):
    ax.bar([i + (k - .5) * w for i in range(len(regs))], [res[r][moteur][0] for r in regs], w,
           yerr=[res[r][moteur][1] for r in regs], capsize=3, color=col, label=moteur)
ax.set_xticks(range(len(regs))); ax.set_xticklabels(regs, fontsize=8, rotation=12)
ax.set_ylabel("Temps (ms)")
nbv = re.search(r"sur les (\d+) mots", mot).group(1)
ax.set_title(f"RegEx sur les {int(nbv):,} mots de l'index : DFA minimal vs simulation du NFA".replace(",", " ") + "\n(moyenne ± écart-type, 10 mesures)")
ax.legend(fontsize=8); fig.tight_layout(); fig.savefig(f"{out}/4_moteurs_regex.png"); plt.close(fig)

# 5. Index vs texte complet a 1684 livres (barres, log)
fig, ax = plt.subplots(figsize=(7, 3.8))
pairs = [("Mot-clé « sargon »\n(index inversé)", req[("motcle", "sargon")][-1], C["motcle"]),
         ("RegEx S(a|g|r)+on\nsur l'index", req[("regex_index", "s(a|g|r)+on")][-1], C["regex_index"]),
         ("« Sargon »\nsur le texte complet", req[("regex_texte", "Sargon")][-1], C["regex_texte"]),
         ("RegEx S(a|g|r)+on\nsur le texte complet", req[("regex_texte", "S(a|g|r)+on")][-1], C["regex_texte"])]
ax.bar(range(4), [p[1][0] for p in pairs], yerr=[p[1][1] for p in pairs], capsize=3, color=[p[2] for p in pairs])
for i, p in enumerate(pairs): ax.text(i, p[1][0] * 1.4, (f"{p[1][0]:,.0f} ms".replace(",", " ") if p[1][0] >= 10 else f"{p[1][0]:.3g} ms"), ha="center", fontsize=8)
ax.set_yscale("log"); ax.set_xticks(range(4)); ax.set_xticklabels([p[0] for p in pairs], fontsize=8)
ax.set_ylabel("Temps (ms, échelle log)")
ax.set_title(f"Index vs texte complet, bibliothèque de {tailles[-1]} livres")
fig.tight_layout(); fig.savefig(f"{out}/5_index_vs_texte.png"); plt.close(fig)

# tableau recapitulatif
with open(f"{out}/tableau_requetes.md", "w", encoding="utf-8") as f:
    f.write("| Type | Requête | " + " | ".join(f"{t} livres" for t in tailles) + " |\n|---|---|" + "---|" * len(tailles) + "\n")
    for (t, q), s in req.items():
        f.write(f"| {t} | `{q}` | " + " | ".join(f"{a:.3g} ± {b:.2g} ms" for a, b, _ in s) + " |\n")
print("ok", tailles)
