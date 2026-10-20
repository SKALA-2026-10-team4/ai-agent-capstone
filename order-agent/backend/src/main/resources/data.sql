INSERT INTO products (sku, name, unit_price, stock_quantity, active) VALUES
 ('CAMP-LANTERN-LED', 'LED 캠핑 랜턴', 30000, 10, true),
 ('CAMP-CHAIR-FOLD', '접이식 캠핑 의자', 45000, 5, true),
 ('CAMP-MUG-STEEL', '스테인리스 캠핑 머그컵', 12000, 0, true)
ON CONFLICT (sku) DO NOTHING;
