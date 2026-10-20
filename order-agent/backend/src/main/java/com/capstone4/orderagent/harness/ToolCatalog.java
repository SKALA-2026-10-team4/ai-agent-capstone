package com.capstone4.orderagent.harness;

import java.util.*;

public final class ToolCatalog {
    private ToolCatalog() {}
    private static final Map<String,String> BINDINGS=Map.of(
        "order-intake","order.inspect", "inventory-check","inventory.reserve", "payment","payment.charge",
        "shipping-reserve","shipping.reserve", "notify","notify.send");
    public static String toolFor(String skill){
        String tool=BINDINGS.get(skill);if(tool==null)throw new AgentException("UNSUPPORTED_SKILL");return tool;
    }
    public static Map<String,Object> arguments(String tool,Map<String,Object> context){
        var args=new LinkedHashMap<String,Object>();args.put("order_id",context.get("order.id"));
        switch(tool){
            case "order.inspect", "inventory.release" -> {}
            case "inventory.reserve" -> args.put("items",context.get("order.items"));
            case "payment.charge" -> {args.put("amount",context.get("order.total"));args.put("method",context.get("order.payment_method"));}
            case "shipping.reserve" -> {
                Object shipping=context.get("order.shipping");
                if(!(shipping instanceof Map<?,?> s))throw new AgentException("CONTRACT_VIOLATION");
                args.put("country",s.get("country"));args.put("express",s.get("express"));
            }
            case "notify.send" -> args.put("customer",context.get("order.customer"));
            default -> throw new AgentException("UNKNOWN_TOOL");
        }
        return args;
    }
    public static Map<String,Object> definition(String tool){
        String description=switch(tool){
            case "order.inspect" -> "Read the accepted order and server-calculated total. Does not create another order.";
            case "inventory.reserve" -> "Atomically reserve all order items or return OUT_OF_STOCK. Arguments: order_id, items[{sku,qty}].";
            case "payment.charge" -> "Mock charge a reserved order. Arguments: order_id, amount (server total), method (card/prepaid).";
            case "shipping.reserve" -> "Reserve shipment only for a paid and reserved order. Arguments: order_id, country, express.";
            case "notify.send" -> "Record success notification only after shipment. Arguments: order_id, customer.";
            default -> throw new AgentException("UNKNOWN_TOOL");
        };
        return Map.of("name",tool,"description",description);
    }
}
