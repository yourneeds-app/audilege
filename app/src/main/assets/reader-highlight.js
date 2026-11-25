(() => {
    const boundaries = new WeakMap();
    const key = 'audilege-sentence';
    let previous = null;
    let companions = new Map(), activeCompanions = [];
    window.audilegeTimedWords = ids => {
        const timed = new Set(ids);
        companions = new Map();
        for (const sentence of document.querySelectorAll('[epub\\:type~="storyteller:sentence-span"]')) {
            if (timed.has(sentence.id)) continue;
            let previousWord = null;
            const leading = [];
            for (const word of sentence.querySelectorAll('[epub\\:type~="storyteller:word-span"]')) {
                if (timed.has(word.id)) {
                    previousWord = word.id;
                    companions.set(previousWord,leading.splice(0));
                } else if (previousWord) companions.get(previousWord).push(word);
                else leading.push(word);
            }
        }
    };

    function sentenceRanges(block) {
        if (boundaries.has(block)) return boundaries.get(block);
        const nodes = [], walker = document.createTreeWalker(block, NodeFilter.SHOW_TEXT);
        let node, length = 0;
        while ((node = walker.nextNode())) {
            if (node.length) {
                nodes.push({node, from: length, to: length + node.length});
                length += node.length;
            }
        }
        const text = nodes.map(n => n.node.data).join('');
        const segments = new Intl.Segmenter(document.documentElement.lang || undefined, {granularity: 'sentence'}).segment(text);
        const result = {nodes, segments};
        boundaries.set(block, result);
        return result;
    }

    function sourceSentence(word) {
        for (let n = word; n && n !== document.body; n = n.parentElement) {
            if ((n.getAttribute('epub:type') || '').split(/\s+/).includes('storyteller:sentence-span')) return n;
        }
        return null;
    }

    function markRange(word) {
        if (!globalThis.CSS?.highlights || !globalThis.Highlight || !Intl.Segmenter) return false;
        const block = word.closest('p,li,h1,h2,h3,h4,h5,h6,blockquote,figcaption') || word.parentElement;
        if (!block) return false;
        const {nodes, segments} = sentenceRanges(block);
        const hit = nodes.find(n => word.contains(n.node));
        if (!hit) return false;
        const part = segments.containing(hit.from);
        if (!part) return false;
        const end = part.index + part.segment.length;
        const first = nodes.find(n => n.to > part.index), last = nodes.find(n => n.to >= end);
        if (!first || !last) return false;
        const range = new Range();
        range.setStart(first.node, part.index - first.from);
        range.setEnd(last.node, end - last.from);
        CSS.highlights.set(key, new Highlight(range));
        return true;
    }

    window.audilegeHighlight = (id, sentence, follow) => {
        const word = id ? document.getElementById(id) : null;
        const target = word && sentence ? sourceSentence(word) : word;
        globalThis.CSS?.highlights?.delete(key);
        const next = target || (word && !markRange(word) ? word : null);
        for (const node of activeCompanions) node.classList.remove('foliocue-current');
        activeCompanions = !sentence && word ? companions.get(word.id) || [] : [];
        for (const node of activeCompanions) node.classList.add('foliocue-current');
        if (previous !== next) {
            previous?.classList.remove('foliocue-current');
            next?.classList.add('foliocue-current');
            previous = next;
        }
        if (!word || !follow) return;
        const box = word.getBoundingClientRect(), margin = Math.min(60, innerHeight * 0.18);
        if (box.top < margin || box.bottom > innerHeight - margin) {
            word.scrollIntoView({behavior: 'instant', block: 'center', inline: 'nearest'});
        }
    };
})();
