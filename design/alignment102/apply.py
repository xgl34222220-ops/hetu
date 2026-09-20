from pathlib import Path
import hashlib
import shutil
r=Path.cwd()
s=r/'android-app/app/src/main/java/io/github/xgl34222220/hetu'
tests=r/'android-app/app/src/test/java/io/github/xgl34222220/hetu'
def replace(path,a,b):
    t=path.read_text();assert t.count(a)==1,(str(path),a,t.count(a));path.write_text(t.replace(a,b))
source=s/'InstrumentWorkspace.kt'
b=source.read_bytes();assert hashlib.sha1(b'blob '+str(len(b)).encode()+b'\0'+b).hexdigest()=='7f51b259a9f416dbfdae70d4cb41cfaf08bf2f58'
t=source.read_text();start=t.index('@Composable\nprivate fun InstrumentCell(');end=t.index('@Composable\nprivate fun InstrumentValue(',start)
source.write_text(t[:start]+t[end:])
replace(s/'WorkspaceCards.kt','    InstrumentBento(runtime, connections, up, down, used, total, count, memory, cpu, onSubscription)','    AlignedInstrumentPanel(runtime, connections, up, down, used, total, count, memory, cpu, onSubscription)')
replace(tests/'InstrumentRenderTest.kt','fourInstrumentsHavePhysicalGuttersAndRealProgress','fourInstrumentsShareOneSurfaceAndRealProgress')
replace(tests/'InstrumentRenderTest.kt','assertTrue("Data cells still share a white table",speed.left-net.right>=11f)','assertEquals("Single-panel divider unexpectedly became a gutter",.5f,speed.left-net.right,1f)')
replace(r/'android-app/app/build.gradle.kts','versionCode = 501','versionCode = 502')
replace(r/'android-app/app/build.gradle.kts','versionName = "0.4.0-test.101"','versionName = "0.4.0-test.102"')
shutil.copyfile(r/'design/alignment102/AlignedInstrumentPanel.kt',s/'AlignedInstrumentPanel.kt')
shutil.copyfile(r/'design/alignment102/InstrumentAlignmentRenderTest.kt',tests/'InstrumentAlignmentRenderTest.kt')
print('Integrated one baseline-aligned panel; no runtime files or settings changed')
