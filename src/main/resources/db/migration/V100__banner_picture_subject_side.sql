-- Where an AI banner picture's subject was made to stand (owner, 6 Nov 2026: a phone showed the empty side of a wide picture
-- while its products were off screen). LEFT, RIGHT or CENTRE of the wide picture; null for an uploaded or product picture, and
-- for those made before this, whose side is not known. The shop's phone layout shows that side.
ALTER TABLE promo_banner_picture ADD COLUMN subject_side VARCHAR(8) CHECK (subject_side IN ('LEFT', 'RIGHT', 'CENTRE'));
