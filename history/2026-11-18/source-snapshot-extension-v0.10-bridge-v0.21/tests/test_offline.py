import importlib
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parents[1] / 'bridge'))
import server
from import_contacts import vcf_contacts


class Tests(unittest.TestCase):
    def test_number_formats(self):
        self.assertTrue(server.valid_number('۰۹۱۲ ۳۴۵ ۶۷۸۹'))
        self.assertTrue(server.valid_number('+98 912 345 6789'))
        self.assertFalse(server.valid_number('110'))
        self.assertFalse(server.valid_number('hello'))

    def test_find_contact_exact(self):
        contacts = {'مريم': '۰۹۱۲-۳۴۵۶۷۸۹', 'Ali': '+989121234567'}
        self.assertEqual(server.find_exact_contact('مریم', contacts),
                         ('مريم', '09123456789'))
        self.assertEqual(server.find_exact_contact('ALI', contacts),
                         ('Ali', '09121234567'))
        self.assertEqual(server.find_exact_contact('JAN', {'J A N': '09121234567'}),
                         ('J A N', '09121234567'))
        self.assertEqual(server.find_exact_contact('J A N', {'JAN': '09121234567'}),
                         ('JAN', '09121234567'))
        self.assertIsNone(server.find_exact_contact('Mary', contacts))
        self.assertEqual(server.find_exact_contact('مریم', {'مریم حسن‌زاده':'09123456789'}), ('مریم حسن‌زاده','09123456789'))
        self.assertIsNone(server.find_exact_contact('مریم', {'مریم حسن‌زاده':'09123456789', 'مریم احمدی':'09123456780'}))

    def test_vcard_import(self):
        content = 'BEGIN:VCARD\nVERSION:3.0\nFN:مریم\nTEL;TYPE=CELL:+989123456789\nEND:VCARD\n'
        self.assertEqual(vcf_contacts(content), {'مریم': '09123456789'})

    def test_no_call_without_exact_contact(self):
        with patch.object(server, 'CONTACTS', Path(tempfile.gettempdir()) / 'not_found_magics123.json'):
            with tempfile.TemporaryDirectory() as d:
                p = Path(d) / 'contacts.json'
                p.write_text('{"Test": "09121234567"}', encoding='utf8')
                with patch.object(server, 'CONTACTS', p):
                    self.assertIsNone(server.find_exact_contact('Other', server.load_contacts()))

    def test_pairing_token_unique_opaque(self):
        with tempfile.TemporaryDirectory() as d:
            with patch.object(server, 'CONFIG', Path(d) / 'settings.json'):
                a = server.load_or_make_settings()
                b = server.load_or_make_settings()
                self.assertEqual(a, b)
                self.assertGreaterEqual(len(a['secret']), 32)

if __name__ == '__main__': unittest.main()
