package io.github.xgl34222220.hetu;

/** Recognizes the private runtime's filtering rule, including child-domain exceptions. */
final class AdblockRuleInspection {
    private AdblockRuleInspection() {}

    static boolean isInjected(String yaml) {
        if(yaml==null||yaml.isEmpty())return false;
        boolean provider=false,allowProvider=false,legacyRule=false,exceptionRule=false;
        String block=ProxyAdblockRules.PROVIDER_NAME,allow=ProxyAdblockRules.ALLOW_PROVIDER_NAME;
        String legacy="RULE-SET,"+block+",REJECT";
        String current="AND,((RULE-SET,"+block+"),(NOT,((RULE-SET,"+allow+")))),REJECT";
        for(String raw:yaml.split("\\r?\\n")) {
            String line=raw.trim();
            if(line.isEmpty()||line.startsWith("#"))continue;
            int comment=line.indexOf('#');
            if(comment>=0)line=line.substring(0,comment).trim();
            if(line.equals(block+":"))provider=true;
            if(line.equals(allow+":"))allowProvider=true;
            if(!line.startsWith("-")||!line.contains(block))continue;
            String rule=line.substring(1).trim();
            if(rule.length()>1&&((rule.startsWith("\"")&&rule.endsWith("\""))
                    ||(rule.startsWith("'")&&rule.endsWith("'"))))rule=rule.substring(1,rule.length()-1);
            rule=rule.replaceAll("\\s+","");
            if(rule.equals(legacy))legacyRule=true;
            if(rule.equals(current))exceptionRule=true;
        }
        return provider&&(legacyRule||(allowProvider&&exceptionRule));
    }
}
