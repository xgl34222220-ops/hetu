"""Resolve the app's actual scroll viewport before sending a physical swipe."""
import re


def scroll_bounds(root, package, allow_missing=False):
    views = [node for node in root.iter('node')
             if node.get('scrollable') == 'true' and node.get('package') == package]
    if not views and allow_missing:
        return None
    assert len(views) == 1, ('Expected one app scrollable viewport', len(views))
    node = views[0]
    assert node.get('enabled') != 'false', 'Disabled app scrollable viewport'
    match = re.fullmatch(r'\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]', node.get('bounds', ''))
    assert match is not None, ('Invalid app scrollable bounds', node.get('bounds'))
    bounds = tuple(map(int, match.groups()))
    x1, y1, x2, y2 = bounds
    assert x1 >= 0 and y1 >= 0 and x2 > x1 and y2 > y1, ('Empty/offscreen scrollable viewport', bounds)
    return bounds


def scroll_swipe(bounds, reverse=False):
    """Keep both gesture endpoints inside the viewport and below the compact header."""
    x1, y1, x2, y2 = bounds
    top = max(y1, 65)
    assert x1 >= 0 and y1 >= 0 and x2 - x1 > 1 and y2 - top > 2, ('No usable scrollable viewport', bounds)
    x = (x1 + x2) // 2
    height = y2 - top
    low = top + height * 4 // 5
    high = top + height // 5
    assert y1 < high < low < y2, ('Swipe outside scrollable viewport', bounds, high, low)
    return (x, high, x, low) if reverse else (x, low, x, high)


def scroll_gesture(bounds, target=None):
    """A missing/lower target scrolls up; a clipped upper target scrolls back down."""
    if bounds is None:
        assert target is not None, 'Missing target and no app scrollable viewport'
        x1, y1, x2, y2 = target
        assert x1 >= 0 and y1 >= 0 and x2 > x1 and y2 > y1, ('Invalid visible target bounds', target)
        return None
    if target is not None:
        _, y1, _, y2 = target
        if y1 < max(bounds[1], 65):
            return scroll_swipe(bounds, reverse=True)
        if y2 <= bounds[3]:
            return None
    return scroll_swipe(bounds)
