#!/usr/bin/env python3
"""Retain the strict 422-test verifier; require all 57 additive PDF/metadata tests."""
import argparse,json,shutil,tempfile
from pathlib import Path
import xml.etree.ElementTree as ET
from verify_auth_test_results import verify_results
EXPECTED={'io.github.xgl34222220.hetu.GoogleConnectionEvidenceTest':8,'io.github.xgl34222220.hetu.tools.ToolsPdf85Test':49}
def verify(results):
    additions={}
    with tempfile.TemporaryDirectory() as tmp:
        for path in results.glob('TEST-*.xml'):
            suite=ET.parse(path).getroot();name=suite.get('name')
            if name in EXPECTED:
                assert name not in additions
                counts={key:int(suite.get(key,0)) for key in ['tests','failures','errors','skipped']}
                assert counts==dict(tests=EXPECTED[name],failures=0,errors=0,skipped=0),(name,counts)
                additions[name]=counts
            else:shutil.copyfile(path,Path(tmp)/path.name)
        baseline=verify_results(Path(tmp),422)
    assert set(additions)==set(EXPECTED),('Missing new suites',additions)
    return {'baseline':baseline,'newSuites':additions,'totalTests':479,'xmlFiles':49}
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--results',type=Path,required=True);a=p.parse_args()
    r=verify(a.results);out=Path('out/verification/pdf85-tests.json');out.parent.mkdir(parents=True,exist_ok=True);out.write_text(json.dumps(r,indent=2)+'\n');print(json.dumps(r))
