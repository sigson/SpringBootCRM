-- ============================================================================
-- REPORTS (typeId=9500) — справочник отчётов (схемы компоновки данных)
-- ============================================================================
-- Элемент справочника хранит всё, что описывает отчёт, четырьмя JSON-реквизитами:
--   schema_json    — наборы данных (упакованная строка с несколькими запросами),
--                    связи наборов, вычисляемые поля, ресурсы, параметры;
--   settings_json  — настройки по умолчанию: структура, отбор, порядок,
--                    условное оформление, параметры вывода;
--   templates_json — макеты областей вывода;
--   forms_json     — формы отчёта из визуального построителя форм.
--
-- Структуру этих деревьев задаёт модуль компоновки (app.modules.dcs) и его
-- конструктор на фронтенде; хост хранит их как текст. VARCHAR(1000000) принимают
-- и H2, и PostgreSQL, поэтому тип колонки одинаков в обеих СУБД.
-- ============================================================================
CREATE TABLE reports (
    id                  UUID              PRIMARY KEY,
    code                VARCHAR(50)       NOT NULL UNIQUE,
    name                VARCHAR(200)      NOT NULL,
    schema_json         VARCHAR(1000000),
    settings_json       VARCHAR(1000000),
    templates_json      VARCHAR(1000000),
    forms_json          VARCHAR(1000000),
    data_source_id      VARCHAR(100),
    enabled             BOOLEAN           NOT NULL DEFAULT TRUE,
    -- ---- AbstractAuditedAggregate audit ----
    own_access          VARCHAR(4000),
    version             BIGINT            NOT NULL DEFAULT 0,
    created_at          TIMESTAMP,
    updated_at          TIMESTAMP,
    created_by_type_id  BIGINT,
    created_by_id       UUID,
    updated_by_type_id  BIGINT,
    updated_by_id       UUID
);

CREATE INDEX idx_reports_code    ON reports (code);
CREATE INDEX idx_reports_code_id ON reports (code, id);
CREATE INDEX idx_reports_name_id ON reports (name, id);

-- Demo report, so the Reports list has content on first start like every other list.
-- Two datasets packed into one string plus a link between them - exactly the shape the
-- SQL Workbench assembles into a join, and the shape the report designer round-trips.
INSERT INTO reports
    (id, code, name, schema_json, settings_json, templates_json, forms_json,
     data_source_id, enabled, version, created_at, updated_at) VALUES
    ('77777777-7777-7777-7777-000000000001', 'RPT001', 'Sales by city and customer',
     '{"packed":"--#query Deals | Deals\nSELECT d.code AS code, d.amount AS amount, d.customer_id AS customer_id, d.expected_close_date AS close_date FROM deals d\n--#query Customers | Customers\nSELECT c.id AS id, c.name AS name, c.city AS city FROM customers c\n--#link Deals -> Customers LEFT ON customer_id = id\n","dataSets":[{"name":"Deals","title":"Deals","type":"query","items":[],"autoFill":true,"fields":[{"name":"code","dataPath":"code","title":"Deal code","role":"dimension","valueType":"string","usableInSelection":true,"usableInFilter":true,"usableInGroup":true,"usableInOrder":true,"ignoreNull":false,"mandatory":false},{"name":"amount","dataPath":"amount","title":"Amount","role":"resource","valueType":"number","usableInSelection":true,"usableInFilter":true,"usableInGroup":false,"usableInOrder":true,"ignoreNull":false,"mandatory":false},{"name":"customer_id","dataPath":"customer_id","title":"Customer id","role":"dimension","valueType":"string","usableInSelection":false,"usableInFilter":false,"usableInGroup":false,"usableInOrder":false,"ignoreNull":false,"mandatory":false},{"name":"close_date","dataPath":"close_date","title":"Expected close","role":"period","valueType":"date","usableInSelection":true,"usableInFilter":true,"usableInGroup":true,"usableInOrder":true,"ignoreNull":false,"mandatory":false}]},{"name":"Customers","title":"Customers","type":"query","items":[],"autoFill":true,"fields":[{"name":"id","dataPath":"id","title":"Id","role":"dimension","valueType":"string","usableInSelection":false,"usableInFilter":false,"usableInGroup":false,"usableInOrder":false,"ignoreNull":false,"mandatory":false},{"name":"name","dataPath":"name","title":"Customer","role":"dimension","valueType":"string","usableInSelection":true,"usableInFilter":true,"usableInGroup":true,"usableInOrder":true,"ignoreNull":false,"mandatory":false},{"name":"city","dataPath":"city","title":"City","role":"dimension","valueType":"string","usableInSelection":true,"usableInFilter":true,"usableInGroup":true,"usableInOrder":true,"ignoreNull":false,"mandatory":false}]}],"links":[{"source":"Deals","target":"Customers","linkType":"left","conditions":[{"sourceExpr":"Deals.customer_id","operator":"=","targetExpr":"Customers.id"}],"rawCondition":null,"disabled":false}],"calculatedFields":[],"resources":[{"name":"total","title":"Amount","expression":"Сумма(Deals.amount)","calcByGroups":[],"format":null},{"name":"deals","title":"Deals","expression":"Количество(Deals.code)","calcByGroups":[],"format":null},{"name":"average","title":"Average deal","expression":"Сумма(Deals.amount) / Количество(Deals.code)","calcByGroups":[],"format":null}],"parameters":[{"name":"MinAmount","title":"Amount from","valueType":"number","value":0,"availableValues":[],"stdPeriod":false,"useRestriction":false,"userVisible":true,"required":false}]}',
     '{"defaultSettings":{"structure":[{"id":"byCity","kind":"grouping","field":"Customers.city","groupingType":"items","title":null,"selection":["Customers.city","total","deals","average"],"order":[],"filter":null,"children":[{"id":"byCustomer","kind":"grouping","field":"Customers.name","groupingType":"items","title":null,"selection":["Customers.name","total","deals"],"order":[],"filter":null,"children":[],"rows":[],"columns":[]}],"rows":[],"columns":[]}],"filter":{"combinator":"and","items":[{"left":"Deals.amount","op":"ge","right":"&MinAmount"}]},"selection":["total","deals"],"order":[{"field":"total","direction":"desc"}],"conditionalAppearance":[{"id":"big","filter":{"combinator":"and","items":[{"left":"total","op":"gt","right":50000}]},"fields":["total"],"appearance":{"textColor":"#1f8c45","bold":true},"areas":["groupTotals"],"disabled":false}],"outputParameters":{"title":"Sales by city and customer","showTitle":true,"verticalTotals":"end","horizontalTotals":"end","groupPlacement":"begin","showParameters":true,"showFilter":false,"maxRows":null},"dataParameters":{"MinAmount":0},"userFields":[]},"variants":[{"id":"byCustomerOnly","name":"By customer only","settings":{"structure":[{"id":"byCustomer","kind":"grouping","field":"Customers.name","groupingType":"items","title":null,"selection":["Customers.name","total","deals"],"order":[],"filter":null,"children":[],"rows":[],"columns":[]}]}}]}',
     '[{"id":"tplHeader","name":"Report header","area":"reportHeader","field":null,"rows":[{"cells":[{"text":"Sales by city and customer","bold":true,"italic":false,"align":"center","textColor":null,"backColor":null,"width":null}]},{"cells":[{"text":"Total amount: [total] in [deals] deal(s)","bold":false,"italic":true,"align":"center","textColor":null,"backColor":null,"width":null}]}]}]',
     '[{"id":"formMain","name":"Main","purpose":"settings","nodes":[{"id":"grpParams","kind":"group","title":"Parameters","target":null,"action":null,"span":12,"readOnly":false,"children":[{"id":"pMin","kind":"parameter","title":"Amount from","target":"MinAmount","action":null,"span":4,"readOnly":false,"children":[]}]},{"id":"grpFilter","kind":"group","title":"Filter","target":null,"action":null,"span":12,"readOnly":false,"children":[{"id":"flt","kind":"filter","title":null,"target":null,"action":null,"span":12,"readOnly":false,"children":[]}]},{"id":"btnCompose","kind":"button","title":"Compose","target":null,"action":"compose","span":3,"readOnly":false,"children":[]},{"id":"btnXlsx","kind":"button","title":"Export to XLSX","target":null,"action":"export-xlsx","span":3,"readOnly":false,"children":[]},{"id":"res","kind":"result","title":null,"target":null,"action":null,"span":12,"readOnly":false,"children":[]}]}]',
     'main', TRUE, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

-- The generated-code counter must know about the seeded row, otherwise the first report
-- created from the UI would be handed RPT001 again. type_id 9500 is not seeded by V1.
INSERT INTO reference_sequences (type_id, next_seq)
VALUES (9500, (SELECT COUNT(*) + 1 FROM reports));
