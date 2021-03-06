# Stubs for eliot._traceback (Python 3)
#
# NOTE: This dynamically typed stub was automatically generated by stubgen.

from ._errors import _error_extraction
from ._message import EXCEPTION_FIELD, REASON_FIELD
from ._util import load_module, safeunicode
from ._validation import Field, MessageType
from typing import Any, Optional

TRACEBACK_MESSAGE: Any

def write_traceback(logger: Optional[Any] = ..., exc_info: Optional[Any] = ...) -> None: ...
def writeFailure(failure: Any, logger: Optional[Any] = ...) -> None: ...
