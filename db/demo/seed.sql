INSERT INTO bank_accounts (organization_name, balance_cents, iban, bic)
VALUES ('Demo', 10000, 'FR761234', 'DEMOBIC')
ON CONFLICT (bic, iban) DO NOTHING;
