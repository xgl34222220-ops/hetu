package io.github.xgl34222220.hetu;

/** Recognizes the private runtime's filtering rule, including child-domain exceptions. */
final class AdblockRuleInspection {
    private AdblockRuleInspection() {}

    static boolean isRuleMode(String mode) {
        return "rule".equalsIgnoreCase(mode==null?"":mode.trim());
    }

    static boolean isBlockingRule(String type,String payload,String policy,boolean disabled) {
        if(disabled||!"REJECT".equalsIgnoreCase(policy))return false;
        String normalized=(payload==null?"":payload).replaceAll("\\s+","");
        if("RuleSet".equalsIgnoreCase(type)||"RULE-SET".equalsIgnoreCase(type))
            return ProxyAdblockRules.PROVIDER_NAME.equals(normalized);
        if(!"AND".equalsIgnoreCase(type))return false;
        // Mihomo Logic.Payload serializes operators and RuleType names for the
        // controller: it does not return the original YAML expression.
        normalized=normalized.replace("RuleSet,","RULE-SET,");
        String block="RULE-SET,"+ProxyAdblockRules.PROVIDER_NAME;
        String allow="RULE-SET,"+ProxyAdblockRules.ALLOW_PROVIDER_NAME;
        return normalized.equals("(("+block+"),(NOT,(("+allow+"))))")
                ||normalized.equals("(("+block+")&&(NOT,(!("+allow+"))))");
    }

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
