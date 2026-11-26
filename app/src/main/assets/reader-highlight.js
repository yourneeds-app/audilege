(() => {
    let boundaries = new WeakMap(), pauseIds = new Set();
    const key = 'audilege-sentence';
    let activeRange = null;
    let companions = new Map(), marked = new Set();
    window.audilegeTimedWords = (ids, pauses = []) => {
        const timed = new Set(ids);
        pauseIds = new Set(pauses);
        boundaries = new WeakMap();
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
        const segments = globalThis.Intl?.Segmenter ?
            new Intl.Segmenter(document.documentElement.lang || undefined, {granularity: 'sentence'}).segment(text) : null;
        const cuts = new Map();
        if (pauseIds.size) for (const part of nodes) {
            for (let parent = part.node.parentElement; parent && parent !== block; parent = parent.parentElement) {
                if (pauseIds.has(parent.id) && !cuts.has(parent.id)) cuts.set(parent.id, part.from);
            }
        }
        const result = {nodes, segments, text, cuts: [...cuts.values()]};
        boundaries.set(block, result);
        return result;
    }

    function sourceSentence(word) {
        for (let n = word; n && n !== document.body; n = n.parentElement) {
            if ((n.getAttribute('epub:type') || '').split(/\s+/).includes('storyteller:sentence-span')) return n;
        }
        return null;
    }

    function markRange(word, phrase, sentence) {
        if (!globalThis.CSS?.highlights || !globalThis.Highlight) return false;
        const block = phrase && sentence ? sentence :
            word.closest('p,li,h1,h2,h3,h4,h5,h6,blockquote,figcaption') || word.parentElement;
        if (!block || (phrase && sentence === word)) return false;
        const {nodes, segments, text, cuts} = sentenceRanges(block);
        const hit = nodes.find(n => word.contains(n.node) && /\S/u.test(n.node.data));
        if (!hit) return false;
        const offset = hit.from + hit.node.data.search(/\S/u);
        const part = phrase && sentence ? {index: 0, segment: text} : segments?.containing(offset);
        if (!part) return false;
        let from = part.index, to = from + part.segment.length;
        if (phrase) [from, to] = audilegePhraseBounds(text, from, to, offset, cuts);
        if (activeRange?.block === block && activeRange.start === from && activeRange.end === to) return true;
        const first = nodes.find(n => n.to > from), last = nodes.find(n => n.to >= to);
        if (!first || !last) return false;
        const range = new Range();
        range.setStart(first.node, from - first.from);
        range.setEnd(last.node, to - last.from);
        CSS.highlights.set(key, new Highlight(range));
        activeRange = {block, start: from, end: to};
        return true;
    }

    window.audilegeHighlight = (id, unit, follow) => {
        const word = id ? document.getElementById(id) : null;
        const phrase = unit === 'phrase';
        const sentence = word && (phrase || unit === 'sentence' || unit === true) ? sourceSentence(word) : null;
        const usesRange = word && (phrase || ((unit === 'sentence' || unit === true) && !sentence)) && markRange(word, phrase, sentence);
        if (!usesRange && activeRange) {
            CSS.highlights.delete(key);
            activeRange = null;
        }
        const next = new Set();
        if (!usesRange && word) next.add(sentence || word);
        if ((unit === "word" || unit === false) && word) for (const node of companions.get(word.id) || []) next.add(node);
        for (const node of marked) if (!next.has(node)) node.classList.remove('foliocue-current');
        for (const node of next) if (!marked.has(node)) node.classList.add('foliocue-current');
        marked = next;
        if (!word || !follow) return;
        const box = word.getBoundingClientRect(), margin = Math.min(60, innerHeight * 0.18);
        if (box.top < margin || box.bottom > innerHeight - margin) {
            word.scrollIntoView({behavior: 'instant', block: 'center', inline: 'nearest'});
        }
    };
})();
