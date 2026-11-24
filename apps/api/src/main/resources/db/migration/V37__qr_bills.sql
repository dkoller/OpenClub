-- Swiss QR bills for fees (OpenAPI 1.26.0, owner-approved 2026-11-22, ADR-0008 addendum 30). The club's own payment
-- details (an IBAN or QR-IBAN of Switzerland or Liechtenstein, the account holder and their postal address), edited
-- with MANAGE_FEES. Not personal data of members. QR bills are generated on demand and never stored.
CREATE TABLE club_payment_details (
    club_id UUID PRIMARY KEY REFERENCES clubs (id) ON DELETE CASCADE,
    -- Compact, upper case; the mod-97 checksum is checked by the API. 21 characters for CH and LI.
    iban TEXT NOT NULL CHECK (iban ~ '^(CH|LI)[0-9]{7}[0-9A-Z]{12}$'),
    holder_name TEXT NOT NULL CHECK (length(holder_name) BETWEEN 1 AND 70 AND holder_name = trim(holder_name)),
    street TEXT CHECK (street IS NULL OR (length(street) BETWEEN 1 AND 70 AND street = trim(street))),
    building_number TEXT CHECK (building_number IS NULL OR (street IS NOT NULL
        AND length(building_number) BETWEEN 1 AND 16 AND building_number = trim(building_number))),
    postal_code TEXT NOT NULL CHECK (length(postal_code) BETWEEN 1 AND 16 AND postal_code = trim(postal_code)),
    town TEXT NOT NULL CHECK (length(town) BETWEEN 1 AND 35 AND town = trim(town)),
    country TEXT NOT NULL CHECK (country IN ('CH', 'LI')),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Stable numbers for the payment reference: one per period and one per member's fee, so the treasurer can map a
-- payment in the bank statement back to the period and member. Existing rows get theirs now. A member who is excluded
-- and included again gets a new fee row and so a new number.
CREATE SEQUENCE fee_period_reference_seq;
ALTER TABLE fee_periods ADD COLUMN reference_no INTEGER NOT NULL DEFAULT nextval('fee_period_reference_seq')
    CHECK (reference_no BETWEEN 1 AND 99999999);
ALTER SEQUENCE fee_period_reference_seq OWNED BY fee_periods.reference_no;
CREATE UNIQUE INDEX fee_periods_reference_unique ON fee_periods (reference_no);

CREATE SEQUENCE member_fee_reference_seq;
ALTER TABLE member_fees ADD COLUMN reference_no INTEGER NOT NULL DEFAULT nextval('member_fee_reference_seq')
    CHECK (reference_no BETWEEN 1 AND 999999999);
ALTER SEQUENCE member_fee_reference_seq OWNED BY member_fees.reference_no;
CREATE UNIQUE INDEX member_fees_reference_unique ON member_fees (reference_no);

-- Changing the account that members pay to is audited (actor and time, identifiers only), so a changed IBAN can be
-- traced to the person who made the change.
ALTER TABLE audit_events DROP CONSTRAINT audit_events_event_type_check;
ALTER TABLE audit_events ADD CONSTRAINT audit_events_event_type_check CHECK (event_type IN (
    'MEMBER_CREATED', 'MEMBER_UPDATED', 'MEMBER_ARCHIVED', 'ROLE_CHANGED',
    'GRANT_ADDED', 'GRANT_REVOKED', 'ADMIN_RECOVERED', 'CLUB_BOOTSTRAPPED', 'MEMBER_ERASED', 'MEMBER_EXPORTED',
    'INVITATION_CREATED', 'INVITATION_ACCEPTED', 'INVITATION_REVOKED', 'ACCOUNT_UNLINKED', 'CONTACT_READ',
    'MEMBER_REACTIVATED', 'GUARDIAN_LINKED', 'GUARDIAN_UNLINKED', 'DEPENDENT_READ', 'DEPENDENT_UPDATED',
    'SELF_CONTACTS_UPDATED', 'FEE_ASSIGNED', 'FEE_CHANGED', 'FEE_PAID', 'FEE_UNPAID', 'FEE_REMOVED', 'MEMBER_LISTED',
    'PUBLIC_LISTING_CHANGED', 'PAYMENT_DETAILS_CHANGED'
));
