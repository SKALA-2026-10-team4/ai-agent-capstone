package com.capstone4.orderagent.harness;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 조건 문법 v2 한 줄(값 이름, 연산자, 값)을 읽고 문맥에서 참인지 판단한다. 단계 Skill frontmatter 의 rules[].when 이 이 문법이다.
 * 연산자는 ==, !=, in, >, >=, <, <= 이고 앞뒤에 공백이 하나 이상 있어야 한다. 값은 '글자', true, false, null, 숫자(-12, 3.5 모양)이고
 * in 뒤에만 [..] 목록(빈 목록 허용)이 온다. 크기 비교(>, >=, <, <=)의 오른쪽은 숫자만 읽고, 실제 값이 숫자가 아니면 거짓이다.
 * true 와 false 는 숫자 1, 0 과 같지 않다. 숫자끼리는 값으로 비교해 90000 과 90000.0 을 같게 본다.
 * 같은 문법을 service/src/main/java/com/capstone4/skillops/scenario/Check.java 가 채점에, skillops-agent/skillops_agent/contracts.py 가
 * 읽기 검사에 쓴다. 세 구현은 docs/contracts/examples/condition-cases.json 으로 같은 결과를 내는지 테스트하며, 이 클래스는
 * order-agent/backend/src/test/java/com/capstone4/orderagent/harness/RuleConditionContractTest.java 가 고정한다.
 * 문법 계약은 docs/plans/2026-10-26-behavior-edit-root-fix.md 2.1 절이고, shared/skill-parser 는 이번 확장에서 고치지 않으므로 주문 Agent 안에 둔다.
 * SkillLoader 가 rules 를 읽을 때 parse 로 형식을 검사하고, OrderHarness.runStep 이 단계 선언 입력으로 holds 를 부른다.
 *
 * @param key      값 이름이다. lookup 으로 문맥에서 실제 값을 찾는다
 * @param operator 연산자 원문이다
 * @param expected 기대 값이다. 글자는 String, 숫자는 BigDecimal, 참과 거짓은 Boolean, null 은 null 이고, in 이면 그 값들의 고칠 수 없는 목록이다
 */
public record RuleCondition(String key, String operator, Object expected) {
    /** 조건 한 줄의 모양이다. 공백 없는 값 이름, 공백, 연산자, 공백, 값 순서이고 앞뒤 공백은 무시한다. 긴 연산자를 먼저 맞춘다. */
    private static final Pattern LINE = Pattern.compile("^\\s*(\\S+)\\s+(==|!=|in|>=|<=|>|<)\\s+(.+?)\\s*$");
    /** 숫자로 읽는 값의 모양이다. +1 이나 1e3 같은 모양은 받지 않는다. */
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?");
    /** 오른쪽에 숫자만 받는 크기 비교 연산자다. */
    private static final Set<String> ORDERING = Set.of(">", ">=", "<", "<=");

    /**
     * 조건 한 줄을 읽는다. null 이거나 모양이 틀리거나 값을 읽을 수 없으면 원문을 밝힌 IllegalArgumentException 을 던진다.
     * in 은 [..] 목록만, 크기 비교는 숫자만 오른쪽에 둘 수 있다. SkillLoader 는 이 예외를 INVALID_SKILL_FORMAT 으로 바꾼다.
     */
    public static RuleCondition parse(String text) {
        var m=text==null?null:LINE.matcher(text);
        if(m==null||!m.matches())throw unreadable(text);
        String operator=m.group(2),raw=m.group(3);
        Object expected;
        if(operator.equals("in")){
            if(!raw.startsWith("[")||!raw.endsWith("]"))throw unreadable(text);
            var items=new ArrayList<Object>();
            for(String part:splitOutsideQuotes(raw.substring(1,raw.length()-1)))items.add(literal(part,text));
            expected=Collections.unmodifiableList(items);
        }else{
            expected=literal(raw,text);
            if(ORDERING.contains(operator)&&!(expected instanceof BigDecimal))throw unreadable(text);
        }
        return new RuleCondition(m.group(1),operator,expected);
    }
    /**
     * 문맥에서 key 의 값을 찾아 조건이 맞으면 true 를 돌려준다. 값이 없으면 null 로 보고 비교한다.
     * 크기 비교는 실제 값이 숫자(Boolean 은 숫자가 아니다)일 때만 참이 될 수 있다.
     */
    public boolean holds(Map<String,?> context) {
        Object actual=lookup(context,key);
        return switch(operator){
            case "==" -> same(actual,expected);
            case "!=" -> !same(actual,expected);
            case "in" -> ((List<?>)expected).stream().anyMatch(e->same(actual,e));
            default -> ordered(actual);
        };
    }
    /**
     * 문맥에서 값 이름 하나를 찾는다. 문맥에 그 이름이 그대로 있으면 그 값이다. 없으면 점으로 나눈 앞부분 가운데 문맥에 있는 가장 긴 것을 고르고,
     * 그 값이 Map 이면 나머지 경로를 점마다 한 칸씩 내려간다. 고른 앞부분에서 길이 끊기면 더 짧은 앞부분으로 다시 찾지 않고 null 이다.
     * 예를 들어 order.shipping.country 는 order.shipping Map 의 country 값이고, order.total.x 는 order.total 이 숫자라 null 이다.
     */
    public static Object lookup(Map<String,?> context,String key) {
        if(context.containsKey(key))return context.get(key);
        for(int dot=key.lastIndexOf('.');dot>0;dot=key.lastIndexOf('.',dot-1)){
            if(!context.containsKey(key.substring(0,dot)))continue;
            Object value=context.get(key.substring(0,dot));
            for(String part:key.substring(dot+1).split("\\.",-1)){
                if(!(value instanceof Map<?,?> map))return null;
                value=map.get(part);
            }
            return value;
        }
        return null;
    }
    /** 크기 비교를 한다. 실제 값이 숫자가 아니거나 숫자로 바꿀 수 없으면(NaN 등) 거짓이다. */
    private boolean ordered(Object actual){
        if(!(actual instanceof Number n))return false;
        int c;
        try{c=new BigDecimal(n.toString()).compareTo((BigDecimal)expected);}catch(NumberFormatException e){return false;}
        return switch(operator){
            case ">" -> c>0;
            case ">=" -> c>=0;
            case "<" -> c<0;
            default -> c<=0;
        };
    }
    /** 값 한 칸을 읽는다. '글자', true, false, null, 숫자 순서로 보고, 어느 것도 아니면 원문 text 를 밝힌 IllegalArgumentException 을 던진다. */
    private static Object literal(String raw,String text){
        String v=raw.trim();
        if(v.length()>=2&&v.startsWith("'")&&v.endsWith("'"))return v.substring(1,v.length()-1);
        if(v.equals("true")||v.equals("false"))return Boolean.valueOf(v);
        if(v.equals("null"))return null;
        if(NUMBER.matcher(v).matches())return new BigDecimal(v);
        throw unreadable(text);
    }
    /** in 목록의 안쪽을 쉼표로 나눈다. 작은따옴표 안의 쉼표는 나누지 않고, 안쪽이 비어 있으면 빈 목록이다. */
    private static List<String> splitOutsideQuotes(String inner){
        var parts=new ArrayList<String>();
        if(inner.isBlank())return parts;
        var current=new StringBuilder();boolean quoted=false;
        for(char c:inner.toCharArray()){
            if(c=='\'')quoted=!quoted;
            if(c==','&&!quoted){parts.add(current.toString());current.setLength(0);}else current.append(c);
        }
        parts.add(current.toString());
        return parts;
    }
    /** 두 값이 같은지 본다. 하나라도 null 이면 둘 다 null 일 때만 같고, 숫자끼리는 BigDecimal 값으로 비교한다. Boolean 은 숫자와 같지 않다. */
    private static boolean same(Object a,Object b){
        if(a==null||b==null)return a==b;
        if(a instanceof Number x&&b instanceof Number y){
            try{return new BigDecimal(x.toString()).compareTo(new BigDecimal(y.toString()))==0;}catch(NumberFormatException e){return false;}
        }
        return a.equals(b);
    }
    /** 읽을 수 없는 조건의 오류를 만든다. 고쳐 쓸 수 있게 예시를 함께 적는다. */
    private static IllegalArgumentException unreadable(String text){
        return new IllegalArgumentException("조건을 읽을 수 없습니다: "+text+" (예: order.total >= 100000)");
    }
}
