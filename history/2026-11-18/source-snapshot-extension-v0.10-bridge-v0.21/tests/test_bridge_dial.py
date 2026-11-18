import sys
import unittest
from pathlib import Path
from unittest.mock import patch
from types import SimpleNamespace
from contextlib import redirect_stdout
from io import StringIO
sys.path.insert(0, str(Path(__file__).parents[1] / 'bridge'))
import server

class BridgeDialTests(unittest.TestCase):
    def test_success_is_not_connection_confirmation(self):
        response = SimpleNamespace(stdout='STEP: verified\nOK: invoked', stderr='', returncode=0)
        with patch.object(server.subprocess, 'run', return_value=response) as run, redirect_stdout(StringIO()):
            ok, message = server.invoke_dial('09123456789')
            self.assertTrue(ok)
            self.assertIn('not yet confirmed', message)
            self.assertEqual(run.call_count, 1)
            self.assertEqual(run.call_args.args[0][-2:], ['-Number','09123456789'])
    def test_failed_dial_not_retried_and_redacted(self):
        response = SimpleNamespace(stdout='STOP [keypad]: 09123456789', stderr='', returncode=2)
        output = StringIO()
        with patch.object(server.subprocess, 'run', return_value=response), redirect_stdout(output):
            ok, message = server.invoke_dial('09123456789')
            self.assertFalse(ok)
            self.assertIn('STOP [keypad]',message)
            self.assertNotIn('09123456789',message + output.getvalue())
    def test_timeout_no_retry(self):
        with patch.object(server.subprocess,'run',side_effect=server.subprocess.TimeoutExpired('powershell',80)) as run:
            ok, message = server.invoke_dial('09123456789')
            self.assertFalse(ok)
            self.assertEqual(run.call_count,1)
