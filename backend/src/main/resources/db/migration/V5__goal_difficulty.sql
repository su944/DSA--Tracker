ALTER TABLE goals ADD COLUMN difficulty TEXT CHECK (difficulty IN ('EASY', 'MEDIUM', 'HARD'));
