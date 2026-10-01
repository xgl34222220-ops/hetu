"""Read-only readiness checks for the fresh emulator's actual home activity."""
import re


def component_name(value):
    match = re.fullmatch(r'([A-Za-z0-9_.]+)/([A-Za-z0-9_.$]+)', value.strip())
    if not match:
        return None
    package, activity = match.groups()
    return package + '/' + (package + activity if activity.startswith('.') else activity)


def home_component(resolution):
    values = [component_name(line) for line in resolution.splitlines()]
    values = [value for value in values if value]
    if len(values) != 1 or not values[0].startswith('com.android.launcher3/'):
        return None
    return values[0]


def home_readiness(component, events, activity, windows):
    """Require a completed home frame and both activity/window focus; ANR never passes."""
    problems = re.findall(r'\bam_anr\b.*', events)
    crashes = re.findall(r'\bam_crash\b.*', events)
    first_frames = re.findall(r'\bwm_activity_launch_time\b[^\n]*\[\s*-?\d+,\s*-?\d+,\s*([^,\]]+)', events)
    resumed = re.findall(r'(?:mResumedActivity|topResumedActivity)[^\n]*\bu\d+\s+([^\s}]+)', activity)
    focused = re.findall(r'mCurrentFocus=Window\{[^\n]*\bu\d+\s+([^\s}]+)', windows)
    drawn_windows = []
    for title, block in re.findall(r'^  Window #\d+ Window\{[^}]*\bu\d+\s+([^\s}]+)\}:\n(.*?)(?=^  Window #|\Z)', windows, re.M | re.S):
        if (re.search(r'\bmHasSurface=true\b', block) and re.search(r'\bshown=true\b', block)
                and re.search(r'\bmDrawState=HAS_DRAWN\b', block) and re.search(r'\bisOnScreen=true\b', block)):
            drawn_windows.append(component_name(title))
    frame_event = bool(component and component in [component_name(x) for x in first_frames])
    window_drawn = bool(component and component in drawn_windows)
    result = {
        'homeComponent': component,
        'homeFirstFrameComplete': frame_event or window_drawn,
        'homeFirstFrameEvent': frame_event,
        'homeWindowDrawn': window_drawn,
        'homeActivityResumed': bool(component and component in [component_name(x) for x in resumed]),
        'homeWindowFocused': bool(component and component in [component_name(x) for x in focused]),
        'anrEvents': problems,
        'crashCount': len(crashes),
    }
    result['ready'] = (all(result[key] for key in ('homeComponent', 'homeFirstFrameComplete', 'homeActivityResumed', 'homeWindowFocused'))
                       and not problems and len(crashes) < 3)
    return result
