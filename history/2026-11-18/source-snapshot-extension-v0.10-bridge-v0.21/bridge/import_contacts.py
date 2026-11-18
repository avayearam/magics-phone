"""Import Android-exported VCF into local name->number mapping (no cloud upload)."""
from __future__ import annotations
import json
from pathlib import Path
import re
import tkinter as tk
from tkinter import filedialog, messagebox
from server import CONTACTS, load_contacts, normalize_name, only_number, valid_number


def vcf_contacts(content: str) -> dict:
    result = {}
    content = re.sub(r'\r\n[ \t]', '', content)
    content = re.sub(r'\n[ \t]', '', content)
    for card in re.findall(r'BEGIN:VCARD(.*?)END:VCARD', content, re.S | re.I):
        match = re.search(r'^FN(?:;[^:]*)?:(.+)$', card, re.M | re.I)
        if not match:
            continue
        name = match.group(1).strip().replace('\\n', ' ').replace('\\,', ',')
        candidates = re.findall(r'^TEL(?:;[^:]*)?:(.+)$', card, re.M | re.I)
        if not candidates:
            continue
        number = only_number(candidates[0].strip())
        if number.startswith('+98') and len(number) == 13:
            number = '0' + number[3:]
        if valid_number(number) and normalize_name(name):
            result[name] = number
    return result


def main():
    root = tk.Tk()
    root.withdraw()
    filename = filedialog.askopenfilename(title='Select exported Android .vcf contacts file',
                                        filetypes=[('vCard files', '*.vcf'), ('All', '*.*')])
    if not filename:
        return
    content = Path(filename).read_text(encoding='utf-8-sig', errors='replace')
    imported = vcf_contacts(content)
    if not imported:
        messagebox.showerror('No contacts', 'No compatible contacts found in this VCF.', parent=root)
        return
    current = load_contacts()
    norm_existing = {normalize_name(k): k for k in current}
    skipped = 0
    added = 0
    for name, number in imported.items():
        if normalize_name(name) in norm_existing:
            skipped += 1
            continue
        current[name] = number
        added += 1
    CONTACTS.write_text(json.dumps(current, indent=2, ensure_ascii=False), encoding='utf-8')
    messagebox.showinfo('Contacts imported',
                        f'Imported: {added}; skipped duplicates: {skipped}.\nSaved only on this PC.', parent=root)


if __name__ == '__main__':
    main()
