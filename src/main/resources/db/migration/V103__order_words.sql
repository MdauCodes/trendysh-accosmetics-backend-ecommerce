-- The shop's word list for reading customers' WhatsApp messages (owner, 15 Nov 2026): what a pasted message's words mean,
-- so staff get the right products out of messages however they are written. Read by the dashboard's paste box.
--   NICKNAME  a phrase customers use for one product option ("ruby gel" -> Gel Polish Ruby Red): variant_id says which.
--   SAME_AS   a spelling or word that means another ("glu" -> "glue", "rangi" -> "colour"): meaning holds the words.
--   IGNORE    a word that is never part of a product ("naomba", "pliz", "boss"): taken out before searching.
-- SUGGESTED words come from staff corrections and only count once someone approves them.
CREATE TABLE order_word (
    id          UUID PRIMARY KEY,
    kind        VARCHAR(10)  NOT NULL CHECK (kind IN ('NICKNAME', 'SAME_AS', 'IGNORE')),
    phrase      VARCHAR(120) NOT NULL,
    meaning     VARCHAR(200),
    variant_id  UUID REFERENCES product_variant(id) ON DELETE CASCADE,
    status      VARCHAR(10)  NOT NULL DEFAULT 'APPROVED' CHECK (status IN ('APPROVED', 'SUGGESTED')),
    added_by    VARCHAR(120),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT order_word_needs_meaning CHECK (
        (kind = 'NICKNAME' AND variant_id IS NOT NULL)
        OR (kind = 'SAME_AS' AND meaning IS NOT NULL AND length(trim(meaning)) > 0)
        OR kind = 'IGNORE')
);

-- One entry per phrase and kind, however it was capitalised.
CREATE UNIQUE INDEX order_word_kind_phrase ON order_word (kind, lower(phrase));

-- Words that are never part of a product: English and Kiswahili/Sheng fillers, politeness, time and asking words.
INSERT INTO order_word (id, kind, phrase, added_by)
SELECT gen_random_uuid(), 'IGNORE', w, 'Starter list'
FROM unnest(ARRAY[
    'naomba', 'nataka', 'ningependa', 'nipe', 'niletee', 'nitumie', 'tuma', 'leta', 'nisaidie', 'tafadhali', 'tafathali',
    'asante', 'asanteni', 'karibu', 'sasa', 'mambo', 'niaje', 'vipi', 'habari', 'hujambo', 'boss', 'bro', 'sis',
    'madam', 'mathe', 'dear', 'pliz', 'plz', 'pls', 'please', 'kindly', 'thanks', 'thank', 'thx', 'ok', 'okay', 'sawa',
    'ile', 'zile', 'hiyo', 'hizo', 'hii', 'hizi', 'ya', 'za', 'wa', 'la', 'cha', 'vya', 'pia', 'tena', 'kama', 'jana',
    'leo', 'kesho', 'last', 'time', 'same', 'again', 'also', 'still', 'then', 'need', 'want', 'like', 'get', 'buy',
    'order', 'send', 'bring', 'deliver', 'kindly', 'some', 'the', 'a', 'an', 'my', 'me', 'for', 'with', 'from',
    'yesterday', 'today', 'tomorrow', 'asap', 'urgent', 'quick', 'quickly', 'hi', 'hello', 'hey', 'morning', 'evening',
    'afternoon', 'good', 'nice', 'na', 'and', 'plus', 'pamoja', 'kwa', 'juu', 'ama', 'au', 'or'
]::text[]) AS w
ON CONFLICT DO NOTHING;

-- Spellings and words that mean another.
INSERT INTO order_word (id, kind, phrase, meaning, added_by)
SELECT gen_random_uuid(), 'SAME_AS', s[1], s[2], 'Starter list'
FROM (VALUES
    (ARRAY['glu', 'glue']), (ARRAY['gluu', 'glue']), (ARRAY['gundi', 'glue']), (ARRAY['polsh', 'polish']),
    (ARRAY['polishi', 'polish']), (ARRAY['pollish', 'polish']), (ARRAY['nailpolish', 'nail polish']),
    (ARRAY['geli', 'gel']), (ARRAY['jeli', 'gel']), (ARRAY['gell', 'gel']), (ARRAY['lashes', 'lash']),
    (ARRAY['lashs', 'lash']), (ARRAY['eyelashes', 'lash']), (ARRAY['eyelash', 'lash']), (ARRAY['kope', 'lash']),
    (ARRAY['nyusi', 'brow']), (ARRAY['eyebrow', 'brow']), (ARRAY['eyebrows', 'brow']), (ARRAY['brows', 'brow']),
    (ARRAY['kucha', 'nail']), (ARRAY['nails', 'nail']), (ARRAY['rangi', 'colour']), (ARRAY['color', 'colour']),
    (ARRAY['colours', 'colour']), (ARRAY['colors', 'colour']), (ARRAY['nyekundu', 'red']), (ARRAY['nyeusi', 'black']),
    (ARRAY['nyeupe', 'white']), (ARRAY['pinki', 'pink']), (ARRAY['blu', 'blue']), (ARRAY['grey', 'gray']),
    (ARRAY['topcoat', 'top coat']), (ARRAY['basecoat', 'base coat']), (ARRAY['uv lamp', 'uv led lamp']),
    (ARRAY['taa', 'lamp']), (ARRAY['mashine', 'machine']), (ARRAY['drili', 'drill']), (ARRAY['brashi', 'brush']),
    (ARRAY['mafuta', 'oil']), (ARRAY['cuticles', 'cuticle']), (ARRAY['tips', 'tip']), (ARRAY['acrylics', 'acrylic']),
    (ARRAY['acrilic', 'acrylic']), (ARRAY['akriliki', 'acrylic']), (ARRAY['remover', 'remover']),
    (ARRAY['wax', 'wax']), (ARRAY['nta', 'wax']), (ARRAY['pcs', 'pieces']), (ARRAY['pc', 'piece'])
) AS v(s)
WHERE s[1] <> s[2]
ON CONFLICT DO NOTHING;
