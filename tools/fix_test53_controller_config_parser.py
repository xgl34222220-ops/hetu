from pathlib import Path

p = Path('android-app/app/src/main/java/io/github/xgl34222220/hetu/RootProxyManager.java')
s = p.read_text()
s = s.replace('import java.util.regex.*;\n', '')
old = '''            Matcher matcher=Pattern.compile("(?m)^\\s*external-controller:\\s*127\\.0\\.0\\.1:(\\d+)\\s*$").matcher(result.output==null?"":result.output);\n            int found=0;\n            while(matcher.find()){\n                int candidate=Integer.parseInt(matcher.group(1));\n                if(candidate>=29090&&candidate<=29149)found=candidate;\n            }\n            return found;'''
new = '''            String output=result.output==null?"":result.output;\n            int found=0;\n            try(BufferedReader reader=new BufferedReader(new StringReader(output))){\n                String line;\n                while((line=reader.readLine())!=null){\n                    String trimmed=line.trim();\n                    String prefix="external-controller:";\n                    if(!trimmed.startsWith(prefix))continue;\n                    String endpoint=trimmed.substring(prefix.length()).trim();\n                    String host="127.0.0.1:";\n                    if(!endpoint.startsWith(host))continue;\n                    try{\n                        int candidate=Integer.parseInt(endpoint.substring(host.length()).trim());\n                        if(candidate>=29090&&candidate<=29149)found=candidate;\n                    }catch(NumberFormatException ignored){ }\n                }\n            }\n            return found;'''
if old not in s:
    raise RuntimeError('live controller regex parser not found')
s = s.replace(old, new, 1)
p.write_text(s)
print('Fixed test53 live controller config parser without regex escapes')
