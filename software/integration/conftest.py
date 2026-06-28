"""Make `mmfree_pack` importable when running pytest from this directory."""

import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
