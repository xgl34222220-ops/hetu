"""Resolve the native container, excluding nested accessibility document nodes."""
import re


def webview_bounds(root, package):
    parents = {child: parent for parent in root.iter() for child in parent}
    views = [node for node in root.iter('node')
             if node.get('class') == 'android.webkit.WebView' and node.get('package') == package]
    view_set = set(views)
    containers = []
    for node in views:
        ancestor = parents.get(node)
        nested = False
        while ancestor is not None:
            if ancestor in view_set:
                nested = True
                break
            ancestor = parents.get(ancestor)
        if not nested:
            containers.append(node)
    assert len(containers) == 1, ('Expected one native WebView container', len(containers), len(views))
    values = re.findall(r'-?\d+', containers[0].get('bounds', ''))
    assert len(values) == 4, ('Invalid native WebView bounds', values)
    x1, y1, x2, y2 = map(int, values)
    assert x1 >= 0 and y1 >= 0 and x2 > x1 and y2 > y1, ('Empty/offscreen WebView container', values)
    return (x1, y1, x2, y2), len(views)
