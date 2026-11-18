import unittest
from pathlib import Path
SOURCE=(Path(__file__).parents[1]/'bridge'/'phone_ui.ps1').read_text()
CALL=SOURCE.split('function Invoke-VerifiedCall',1)[1].split('\ntry {',1)[0]
MAIN=SOURCE.split('\ntry {',1)[1]
DIRECT=MAIN.split('if ($editMode) {',1)[1].split('\n    } else {',1)[0]
FOCUS=SOURCE.split('function Focus-VerifiedEdit',1)[1].split('function Convert-SelectedNumber',1)[0]
SELECT=SOURCE.split('function Get-SelectedTarget',1)[1].split('function Reveal-CallButton',1)[0]
REVEAL=SOURCE.split('function Reveal-CallButton',1)[1].split('function Focus-VerifiedCallButton',1)[0]
COMPLETE=SOURCE.split('function Complete-SelectedCall',1)[1].split('function Invoke-VerifiedCall',1)[0]
class CallGuardTests(unittest.TestCase):
    def test_direct_verified_edit_preserved(self):
        self.assertLess(DIRECT.index('Get-ControlText $fresh'),DIRECT.index('$vp.SetValue($Number)'))
        self.assertLess(DIRECT.index('$vp.SetValue($Number)'),DIRECT.index('Wait-EditNumber $Number'))
        self.assertNotIn('SendKeys',DIRECT)
    def test_enter_once_after_fresh_validation(self):
        action="[System.Windows.Forms.SendKeys]::SendWait('{ENTER}')"
        self.assertEqual(SOURCE.count(action),1)
        self.assertEqual(MAIN.count('Invoke-VerifiedCall $Number $editMode'),1)
        for guard in ['Focus-VerifiedEdit $edit $root','Test-SameElement $edit $freshEdit','Assert-ExactNumber $freshPane $expected $true','Test-EditFocus $freshEdit $root']:
            self.assertLess(CALL.index(guard),CALL.index(action))
        self.assertLess(CALL.index(action),CALL.index('return\n    }'))
    def test_keypad_call_exclusive(self):
        action="Invoke-Control $freshCall 'ButtonCall'"
        self.assertEqual(CALL.count(action),1)
        self.assertLess(CALL.index('return\n    }'),CALL.index(action))
        self.assertLess(CALL.index('Assert-ExactNumber $freshPane $expected $false'),CALL.index(action))
    def test_enter_then_green_call(self):
        self.assertLess(CALL.index("SendWait('{ENTER}')"),CALL.index('Complete-SelectedCall $expected $root'))
        self.assertLess(CALL.index('Complete-SelectedCall $expected $root'),CALL.index('return\n    }'))
        action="[System.Windows.Forms.SendKeys]::SendWait(' ')"
        self.assertEqual(COMPLETE.count(action),1)
        for guard in ['Get-SelectedTarget $freshPane $expected $root','Test-SameElement $call $freshCall','Test-EditFocus $freshCall $root','Focus-VerifiedCallButton $call $root']:
            self.assertLess(COMPLETE.index(guard),COMPLETE.index(action))
        self.assertLess(COMPLETE.index(action),COMPLETE.rindex('return\n'))
    def test_no_double_activation_or_invoke(self):
        action="[System.Windows.Forms.SendKeys]::SendWait(' ')"
        self.assertNotIn('.Invoke()',COMPLETE)
        self.assertNotIn('::mouse_event',COMPLETE)
        self.assertNotIn('Invoke-Control',COMPLETE)
        self.assertNotIn('catch',COMPLETE[COMPLETE.index(action):])
        self.assertIn('return',COMPLETE[COMPLETE.index(action):])
    def test_space_requires_visible_verified_button(self):
        for guard in ['Get-SelectedTarget $pane $expected $root -AllowHiddenCall','if ($call.Current.IsOffscreen)','Get-SelectedTarget $freshPane $expected $root','if (-not (Test-EditFocus $freshCall $root))']:
            self.assertIn(guard,COMPLETE)
    def test_focus_is_preparation_not_activation(self):
        focus=SOURCE.split('function Focus-VerifiedCallButton',1)[1].split('function Complete-SelectedCall',1)[0]
        self.assertIn('$call.SetFocus()',focus)
        self.assertIn('Test-EditFocus $call $root',focus)
        for action in ['.Invoke()', 'SendWait', 'mouse_event']:
            self.assertNotIn(action,focus)
    def test_selected_number_guard(self):
        for guard in ['GetForegroundWindow','Get-RootWindow $call','IsNullOrWhiteSpace','Test-Within $item $edit','Test-Within $item $keypad','Convert-SelectedNumber','if ($value -cne $expected)','if ($matches -eq 0)']:
            self.assertIn(guard,SELECT)
        self.assertIn("Get-Unique $pane 'ButtonCall' -AllowHidden", SELECT)
        self.assertNotIn('Test-EditFocus',SELECT)
    def test_reveal_only_approved_pane(self):
        self.assertIn('GetForegroundWindow',REVEAL)
        self.assertIn('Test-Within $node $pane',REVEAL)
        self.assertIn('ScrollIntoView',REVEAL)
        self.assertIn('ShowWindow($root,3)',REVEAL)
        self.assertIn('IsZoomed($root)',REVEAL)
        self.assertLess(COMPLETE.index('Reveal-CallButton'),COMPLETE.index('Get-SelectedTarget $freshPane'))
    def test_failed_ready_reports_reason(self):
        self.assertIn('waiting for search box to clear',SELECT)
        self.assertIn('ButtonCall not found',SELECT)
        self.assertIn('No additional action sent:',COMPLETE)
    def test_international_phone_number_format(self):
        block=SOURCE.split('function Convert-SelectedNumber',1)[1].split('function Get-SelectedTarget',1)[0]
        self.assertIn("'^\\+98[0-9]{10}$'",block)
        self.assertIn("'0' + $clean.Substring(3)",block)
        self.assertIn("'^0[0-9]{9,11}$'",block)
    def test_visible_conflicting_number_stops(self):
        self.assertIn('Visible selected phone number conflicts with approved number; no Call sent.',SELECT)
    def test_search_editor_does_not_read_keypad_accumulator_before_enter(self):
        block=SOURCE.split('function Assert-ExactNumber',1)[1].split('function Wait-EditNumber',1)[0]
        edit,keypad=block.split('} else {',1)
        self.assertIn('Get-DialerEdit $pane',edit)
        self.assertIn('(Normalize-Display (Get-ControlText $edit)) -cne $expected',edit)
        self.assertNotIn("Get-Unique $pane 'AccumulatorTextBlock'",edit)
        self.assertNotIn('Get-NumberDisplay',edit)
        self.assertIn('Get-NumberDisplay $pane',keypad)
    def test_post_enter_selected_target_remains_independent(self):
        self.assertIn('if ($value -cne $expected)',SELECT)
        self.assertIn('if ($matches -eq 0)',SELECT)
        self.assertLess(COMPLETE.index('Get-SelectedTarget $freshPane'),COMPLETE.index("SendWait(' ')"))
    def test_focus_validates_process_window_and_descendant(self):
        block=SOURCE.split('function Test-EditFocus',1)[1].split('function Write-FocusDiagnostic',1)[0]
        for guard in ['GetForegroundWindow','Is-PhoneProcess $focused','Get-RootWindow $focused','Test-Within $focused $edit']:
            self.assertIn(guard,block)
    def test_click_only_verified_edit(self):
        self.assertEqual(FOCUS.count('::mouse_event(2,'),1)
        self.assertEqual(FOCUS.count('::mouse_event(4,'),1)
        self.assertEqual(FOCUS.count('Test-PointOnEdit $edit $root $x $y'),2)
        self.assertLess(FOCUS.rindex('Test-PointOnEdit'),FOCUS.index('::mouse_event(2,'))
        self.assertIn('WindowFromPoint',SOURCE)
        self.assertIn('Test-Within $hit $edit',SOURCE)
    def test_no_retry_after_input_failure(self):
        self.assertIn('if (-not (Wait-EditNumber $Number)) { throw',DIRECT)
        self.assertIn("$script:stage = 'keypad'",MAIN)
if __name__=='__main__': unittest.main()
