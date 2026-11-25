// The original EPUB nodes stay in place so that bookmarks and server CFIs remain valid.
(() => {
    if (window.audilegePages) return;
    let index = 0;
    let anchor = null;
    const active = () => document.documentElement.hasAttribute('data-audilege-pages');
    const stride = () => Math.max(1, document.documentElement.clientWidth);
    const count = () => Math.max(1, Math.round(document.body.scrollWidth / stride()));
    const firstText = () => {
        const x = parseFloat(getComputedStyle(document.body).paddingLeft) + 2;
        for (let y = 12; y < innerHeight; y += 8) {
            const range = document.caretRangeFromPoint(x, y);
            if (range?.startContainer.nodeType === Node.TEXT_NODE &&
                Math.abs(range.getBoundingClientRect().top - y) < 24) return range;
        }
        const element = document.elementFromPoint(innerWidth / 2, innerHeight / 2);
        return element && element !== document.body && document.body.contains(element) ? element : null;
    };
    function show(page) {
        index = Math.max(0, Math.min(count() - 1, page));
        document.body.style.setProperty('--audilege-page-x', (-index * stride()) + 'px');
        window.scrollTo(0, 0);
        anchor = firstText();
    }
    function reveal(target) {
        if (!target) return;
        const rect = target.getClientRects()[0];
        if (rect) show(index + Math.floor((rect.left + 1) / stride()));
    }
    function reflow() {
        if (!active()) return;
        // Reset the translation before measuring a range after a viewport width change.
        const previous = index;
        index = 0;
        document.body.style.setProperty('--audilege-page-x', '0px');
        if ((anchor?.startContainer || anchor)?.isConnected) reveal(anchor);
        else show(previous);
    }
    window.audilegePages = {
        active, show, reveal, reflow,
        beforeStyle() { anchor = firstText() || anchor; },
        restoreScroll() {
            if (anchor) window.scrollBy(0, anchor.getBoundingClientRect().top - 12);
            index = 0;
        },
        state: () => [index, count()],
        turn(direction) {
            const next = index + direction;
            if (next < 0 || next >= count()) return false;
            show(next);
            return true;
        }
    };
    window.addEventListener('resize', reflow);
    document.fonts?.addEventListener('loadingdone', reflow);
})();
