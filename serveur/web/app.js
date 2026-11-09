/*
 * Bibliothèque DAAR — frontend (Vue 3, sans étape de build).
 *
 * Deux vues, choisies d'après l'adresse (hash) pour que « Précédent » et le partage de lien marchent :
 *   #/?q=sargon&type=motcle&tri=occurrences   -> vue Recherche (résultats + suggestions)
 *   #/livre/56667?q=sargon&type=motcle        -> vue Livre (détail, centralité, livres proches, lecture)
 *
 * Types de recherche :
 *   motcle   -> GET /api/search?q=...                       (mot exact de l'index)
 *   contient -> GET /api/search?q=...&mode=contient         (mots de l'index contenant q, KMP)
 *   regex    -> GET /api/advanced-search?regex=...          (RegEx sur les mots de l'index, DFA du projet 1)
 *   texte    -> GET /api/advanced-search?regex=...&cible=texte   (RegEx sur le texte complet, lent)
 */
const { createApp, ref, reactive, computed, watch, onMounted } = Vue;

// ------------------------------------------------------------------ outils

const nf = new Intl.NumberFormat('fr-FR');
const fmt = n => nf.format(n);
const fmtSci = x => (x === 0 ? '0' : x < 0.001 ? x.toExponential(2) : x.toFixed(4));

async function api(chemin, options) {
  const rep = await fetch(chemin, options);
  const type = rep.headers.get('Content-Type') || '';
  const corps = type.includes('json') ? await rep.json() : await rep.text();
  if (!rep.ok) throw new Error(corps && corps.erreur ? corps.erreur : 'Erreur ' + rep.status);
  return corps;
}

/** Lit l'adresse courante : { vue, id, params } */
function lireRoute() {
  const h = location.hash.replace(/^#/, '') || '/';
  const [chemin, requete = ''] = h.split('?');
  const params = Object.fromEntries(new URLSearchParams(requete));
  const m = chemin.match(/^\/livre\/(\d+)/);
  return m ? { vue: 'livre', id: +m[1], params } : { vue: 'recherche', id: null, params };
}

function lien(chemin, params) {
  const p = new URLSearchParams(Object.entries(params || {}).filter(([, v]) => v !== '' && v != null));
  const s = p.toString();
  return '#' + chemin + (s ? '?' + s : '');
}

const TYPES = [
  { id: 'motcle',   nom: 'Mot-clé',     aide: 'Livres dont l’index contient exactement ce mot. Ex. : <code>sargon</code>' },
  { id: 'contient', nom: 'Contient',    aide: 'Livres dont un mot de l’index contient ce texte (KMP). Ex. : <code>babylon</code> trouve aussi « babylonian ».' },
  { id: 'regex',    nom: 'RegEx',       aide: 'Livres dont un mot de l’index est reconnu par la RegEx (automate du projet 1). Opérateurs : <code>( ) | * + .</code> Ex. : <code>S(a|g|r)+on</code>' },
  { id: 'texte',    nom: 'RegEx texte', aide: 'RegEx sur le texte complet de chaque livre, comme egrep (respecte la casse, beaucoup plus lent).' },
];
const TRIS = [
  ['pertinence', 'Pertinence'], ['occurrences', 'Occurrences'], ['frequence', 'Fréquence (occ. / taille)'], ['pagerank', 'PageRank'],
  ['closeness', 'Closeness'], ['betweenness', 'Betweenness'], ['clics', 'Les plus consultés'],
];

// ------------------------------------------------------------------ composants

/** Couverture du livre (image Gutenberg si disponible, sinon reliure de couleur avec l'initiale). */
const Couverture = {
  props: { livre: Object, grande: Boolean },
  setup(props) {
    const ok = ref(true);
    const initiale = computed(() => (props.livre.titre || '?').replace(/^(the|a|an)\s+/i, '').charAt(0).toUpperCase());
    return { ok, initiale };
  },
  template: `
    <div class="couv" :class="['c' + (livre.id % 3), { grande }]" aria-hidden="true">
      {{ initiale }}
      <img v-if="ok && livre.couverture" :src="livre.couverture" alt="" loading="lazy" @error="ok = false">
    </div>`,
};

/** Une ligne de résultat (ou de suggestion). */
const CarteLivre = {
  components: { Couverture },
  props: { livre: Object, href: String, tri: String, compact: Boolean, via: String },
  setup() { return { fmt, fmtSci }; },
  template: `
    <a class="livre" :href="href">
      <Couverture :livre="livre" />
      <div>
        <p class="titre">{{ livre.titre }}</p>
        <p class="auteur">{{ livre.auteur || 'Auteur inconnu' }}</p>
        <div v-if="!compact" class="metriques">
          <span :class="{ on: tri === 'frequence' }">{{ fmt(livre.nbMots) }} mots</span>
          <span :class="{ on: tri === 'pagerank' }">PageRank {{ fmtSci(livre.pagerank) }}</span>
          <span :class="{ on: tri === 'closeness' }">closeness {{ fmtSci(livre.closeness) }}</span>
          <span :class="{ on: tri === 'betweenness' }">betweenness {{ fmtSci(livre.betweenness) }}</span>
          <span v-if="livre.clics" :class="{ on: tri === 'clics' }">{{ livre.clics }} consultation(s)</span>
        </div>
        <div v-if="via" class="via">{{ via }}</div>
      </div>
      <div v-if="!compact && livre.occurrences != null" class="rang"><b>{{ fmt(livre.occurrences) }}</b>occurrence{{ livre.occurrences > 1 ? 's' : '' }}</div>
    </a>`,
};

/** Vue 1 : recherche. */
const VueRecherche = {
  components: { CarteLivre },
  props: { params: Object },
  setup(props) {
    const saisie = ref(props.params.q || '');
    const type = ref(props.params.type || 'motcle');
    const tri = ref(props.params.tri || 'pertinence');
    const limite = ref(50);
    const rep = ref(null), erreur = ref(''), charge = ref(false);
    const accueil = reactive({ pagerank: [], clics: [], betweenness: [] });

    const estRegex = computed(() => type.value === 'regex' || type.value === 'texte');
    const aide = computed(() => TYPES.find(t => t.id === type.value).aide);
    // titres des livres cités dans « Proche de : » (résultats ET suggestions : une suggestion de 2e niveau
    // est proche d'une autre suggestion)
    const titres = computed(() => Object.fromEntries(
      [...(rep.value?.resultats || []), ...(rep.value?.suggestions || [])].map(r => [r.id, r.titre])));

    function lancer() {
      const q = saisie.value.trim();
      if (!q) return;
      location.hash = lien('/', { q, type: type.value, tri: tri.value });
    }

    async function charger() {
      const q = props.params.q;
      erreur.value = '';
      if (!q) { rep.value = null; chargerAccueil(); return; }
      charge.value = true;
      const t = props.params.type || 'motcle';
      const commun = { tri: props.params.tri || 'pertinence', limite: limite.value };
      const url = (t === 'motcle' || t === 'contient')
        ? '/api/search?' + new URLSearchParams({ q, ...(t === 'contient' ? { mode: 'contient' } : {}), ...commun })
        : '/api/advanced-search?' + new URLSearchParams({ regex: q, ...(t === 'texte' ? { cible: 'texte' } : {}), ...commun });
      try { rep.value = await api(url); }
      catch (e) { rep.value = null; erreur.value = e.message; }
      finally { charge.value = false; }
    }

    async function chargerAccueil() {
      for (const t of ['pagerank', 'clics', 'betweenness'])
        api('/api/top?tri=' + t + '&limite=' + (t === 'clics' ? 5 : 8)).then(r => {
          accueil[t] = t === 'clics' ? r.livres.filter(l => l.clics > 0) : r.livres;
        }).catch(() => {});
    }

    function plus() { limite.value += 50; charger(); }
    function exemple(q, t) { saisie.value = q; type.value = t; lancer(); }
    function lienLivre(id) { return lien('/livre/' + id, { q: props.params.q, type: props.params.type }); }
    function changerTri() { if (props.params.q) lancer(); }

    watch(() => props.params, p => {
      saisie.value = p.q || ''; type.value = p.type || 'motcle'; tri.value = p.tri || 'pertinence';
      limite.value = 50; charger();
    }, { deep: true });
    onMounted(charger);

    return { saisie, type, tri, rep, erreur, charge, accueil, estRegex, aide, titres, TYPES, TRIS,
             lancer, plus, exemple, lienLivre, changerTri, fmt };
  },
  template: `
    <form class="recherche" role="search" @submit.prevent="lancer">
      <div class="ligne">
        <input class="champ" :class="{ regex: estRegex }" v-model="saisie" :placeholder="estRegex ? 'Expression régulière, ex. S(a|g|r)+on' : 'Rechercher un mot, ex. sargon'"
               autocapitalize="off" autocomplete="off" spellcheck="false" aria-label="Recherche">
        <button class="btn" :disabled="charge">{{ charge ? '…' : 'Chercher' }}</button>
      </div>
      <div class="options">
        <div class="segments" role="radiogroup" aria-label="Type de recherche">
          <button v-for="t in TYPES" :key="t.id" type="button" :class="{ actif: type === t.id }"
                  role="radio" :aria-checked="type === t.id" @click="type = t.id">{{ t.nom }}</button>
        </div>
        <label>Classer par
          <select v-model="tri" @change="changerTri">
            <option v-for="[v, n] in TRIS" :key="v" :value="v">{{ n }}</option>
          </select>
        </label>
      </div>
      <div class="aide" v-html="aide"></div>
    </form>

    <div v-if="erreur" class="erreur">{{ erreur }}</div>
    <div v-else-if="charge && !rep" class="charge">Recherche en cours…</div>

    <template v-else-if="rep">
      <p class="etat">
        <strong>{{ fmt(rep.nbResultats) }}</strong> livre{{ rep.nbResultats > 1 ? 's' : '' }}
        · {{ rep.tempsMs.toLocaleString('fr-FR') }} ms · {{ rep.moteur }}
      </p>
      <div v-if="rep.nbMotsReconnus > 1 || (type === 'regex' && rep.nbMotsReconnus)" class="mots">
        <span class="etat" style="margin:0">{{ fmt(rep.nbMotsReconnus) }} mot(s) reconnu(s) :</span>
        <a v-for="m in rep.motsReconnus" :key="m" class="puce" :href="'#/?q=' + m + '&type=motcle'">{{ m }}</a>
        <span v-if="rep.nbMotsReconnus > rep.motsReconnus.length" class="puce">…</span>
      </div>

      <div class="grille">
        <section>
          <h2>Résultats <small>classés par {{ TRIS.find(t => t[0] === (params.tri || 'pertinence'))[1].toLowerCase() }}</small></h2>
          <p v-if="!rep.resultats.length" class="vide">Aucun livre ne correspond à cette recherche.</p>
          <ul class="livres">
            <li v-for="r in rep.resultats" :key="r.id">
              <CarteLivre :livre="r" :href="lienLivre(r.id)" :tri="params.tri || 'pertinence'" />
            </li>
          </ul>
          <button v-if="rep.resultats.length < rep.nbResultats" class="plus" @click="plus" :disabled="charge">
            Afficher plus ({{ fmt(rep.nbResultats - rep.resultats.length) }} restants)
          </button>
        </section>

        <aside class="suggestions">
          <h2>Suggestions <small>voisins dans le graphe de Jaccard</small></h2>
          <p v-if="!rep.suggestions.length" class="vide">Pas de suggestion pour cette recherche.</p>
          <ul class="livres">
            <li v-for="s in rep.suggestions" :key="s.id">
              <CarteLivre :livre="s" :href="lienLivre(s.id)" compact
                :via="'Proche de : ' + s.voisinDe.map(v => titres[v] || ('n°' + v)).join(', ') + ' · similarité ' + Math.round((1 - s.distance) * 100) + ' %'" />
            </li>
          </ul>
        </aside>
      </div>
    </template>

    <template v-else>
      <div class="accueil-exemples">
        <h2>Essayez</h2>
        <div class="mots">
          <a class="puce" @click.prevent="exemple('sargon', 'motcle')" href="#">sargon</a>
          <a class="puce" @click.prevent="exemple('babylon', 'contient')" href="#">contient : babylon</a>
          <a class="puce" @click.prevent="exemple('S(a|g|r)+on', 'regex')" href="#"><code>S(a|g|r)+on</code></a>
          <a class="puce" @click.prevent="exemple('whal(e|es|ing)', 'regex')" href="#"><code>whal(e|es|ing)</code></a>
          <a class="puce" @click.prevent="exemple('elizabeth', 'motcle')" href="#">elizabeth</a>
        </div>
      </div>
      <div class="grille">
        <section>
          <h2>Livres les plus centraux <small>PageRank dans le graphe de Jaccard</small></h2>
          <ul class="livres">
            <li v-for="l in accueil.pagerank" :key="l.id"><CarteLivre :livre="l" :href="lienLivre(l.id)" tri="pagerank" /></li>
          </ul>
        </section>
        <aside class="suggestions">
          <template v-if="accueil.clics.length">
            <h2>Les plus consultés</h2>
            <ul class="livres">
              <li v-for="l in accueil.clics" :key="l.id"><CarteLivre :livre="l" :href="lienLivre(l.id)" compact :via="l.clics + ' consultation(s)'" /></li>
            </ul>
          </template>
          <h2>Livres « ponts » <small>betweenness</small></h2>
          <ul class="livres">
            <li v-for="l in accueil.betweenness" :key="l.id"><CarteLivre :livre="l" :href="lienLivre(l.id)" compact /></li>
          </ul>
        </aside>
      </div>
    </template>`,
};

/** Vue 2 : détail d'un livre. */
const VueLivre = {
  components: { Couverture, CarteLivre },
  props: { id: Number, params: Object },
  setup(props) {
    const livre = ref(null), erreur = ref('');
    const texte = ref(''), affiche = ref(0), chargeTexte = ref(false);
    const MORCEAU = 150000;

    async function charger() {
      livre.value = null; erreur.value = ''; texte.value = ''; affiche.value = 0;
      try {
        livre.value = await api('/api/books/' + props.id);
        api('/api/books/' + props.id + '/click', { method: 'POST' })      // fonctionnalité implicite : clics
          .then(r => { if (livre.value) livre.value.clics = r.clics; }).catch(() => {});
        window.scrollTo(0, 0);
      } catch (e) { erreur.value = e.message; }
    }

    async function lire() {
      if (texte.value) { affiche.value = affiche.value ? 0 : MORCEAU; return; }
      chargeTexte.value = true;
      try { texte.value = await api('/api/books/' + props.id + '/text'); affiche.value = MORCEAU; }
      catch (e) { erreur.value = e.message; }
      finally { chargeTexte.value = false; }
    }

    // Surlignage du mot recherché (recherche par mot-clé ou « contient »)
    const extrait = computed(() => {
      const brut = texte.value.slice(0, affiche.value);
      const esc = s => s.replace(/[&<>]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c]));
      const q = (props.params.q || '').trim();
      const t = props.params.type || 'motcle';
      if (!q || !/^[a-z]+$/i.test(q) || (t !== 'motcle' && t !== 'contient')) return { html: esc(brut), n: 0 };
      const re = new RegExp(t === 'motcle' ? '(?<![A-Za-z])(' + q + ')(?![A-Za-z])' : '(' + q + ')', 'gi');
      let n = 0;
      const html = esc(brut).replace(re, m => { n++; return '<mark>' + m + '</mark>'; });
      return { html, n };
    });

    const retour = computed(() => props.params.q ? lien('/', { q: props.params.q, type: props.params.type }) : '#/');

    watch(() => props.id, charger);
    onMounted(charger);
    return { livre, erreur, texte, affiche, chargeTexte, extrait, retour, lire, MORCEAU, fmt, fmtSci, lien,
             plus: () => { affiche.value += MORCEAU; } };
  },
  template: `
    <a class="retour" :href="retour">← {{ params.q ? 'Retour aux résultats pour « ' + params.q + ' »' : 'Accueil' }}</a>
    <div v-if="erreur" class="erreur">{{ erreur }}</div>
    <div v-else-if="!livre" class="charge">Chargement…</div>
    <template v-else>
      <div class="fiche">
        <Couverture :livre="livre" grande />
        <div>
          <h1>{{ livre.titre }}</h1>
          <p class="auteur">{{ livre.auteur || 'Auteur inconnu' }} · Projet Gutenberg n° {{ livre.id }}</p>
          <div class="chiffres">
            <div class="chiffre"><span>Taille</span><b>{{ fmt(livre.nbMots) }}</b><small>mots</small></div>
            <div class="chiffre"><span>Voisins</span><b>{{ livre.degre }}</b><small>degré dans le graphe</small></div>
            <div class="chiffre"><span>PageRank</span><b>{{ fmtSci(livre.pagerank) }}</b></div>
            <div class="chiffre"><span>Closeness</span><b>{{ fmtSci(livre.closeness) }}</b></div>
            <div class="chiffre"><span>Betweenness</span><b>{{ fmtSci(livre.betweenness) }}</b></div>
            <div class="chiffre"><span>Consultations</span><b>{{ livre.clics }}</b></div>
          </div>
          <div class="actions">
            <button class="btn" @click="lire" :disabled="chargeTexte">
              {{ chargeTexte ? 'Chargement…' : (affiche ? 'Masquer le texte' : 'Lire le livre') }}
            </button>
            <a class="btn secondaire" :href="'https://www.gutenberg.org/ebooks/' + livre.id" target="_blank" rel="noopener">Page Gutenberg ↗</a>
          </div>
        </div>
      </div>

      <div v-if="affiche" class="lecture">
        <p v-if="extrait.n" class="etat" style="margin-top:0">« {{ params.q }} » surligné {{ fmt(extrait.n) }} fois dans cet extrait.</p>
        <pre v-html="extrait.html"></pre>
        <button v-if="affiche < texte.length" class="plus" @click="plus">Lire la suite ({{ Math.round(100 * affiche / texte.length) }} % affiché)</button>
      </div>

      <h2>Livres proches <small>voisins dans le graphe de Jaccard, du plus similaire au moins similaire</small></h2>
      <ul class="livres">
        <li v-for="v in livre.voisins" :key="v.id">
          <CarteLivre :livre="v" :href="lien('/livre/' + v.id, { q: params.q, type: params.type })" compact
                      :via="'Similarité ' + Math.round((1 - v.distance) * 100) + ' % · distance de Jaccard ' + v.distance.toFixed(3)" />
        </li>
      </ul>
    </template>`,
};

// ------------------------------------------------------------------ application

createApp({
  components: { VueRecherche, VueLivre },
  setup() {
    const route = ref(lireRoute());
    const stats = ref(null);
    window.addEventListener('hashchange', () => { route.value = lireRoute(); });
    api('/api/stats').then(s => { stats.value = s; }).catch(() => {});
    return { route, stats, fmt };
  },
  template: `
    <div class="page">
      <header class="entete">
        <a class="marque" href="#/">Bibliothèque <span>DAAR</span></a>
        <div class="infos" v-if="stats">{{ fmt(stats.nbLivres) }} livres · {{ fmt(stats.nbMotsDistincts) }} mots indexés</div>
      </header>
      <VueLivre v-if="route.vue === 'livre'" :id="route.id" :params="route.params" />
      <VueRecherche v-else :params="route.params" />
      <footer class="pied">Projet DAAR 2026 — Sorbonne Université · Livres du Projet Gutenberg (gutenberg.org)</footer>
    </div>`,
}).mount('#app');
