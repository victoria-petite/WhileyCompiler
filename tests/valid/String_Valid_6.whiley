import whiley.lang.System

method main(System.Console sys) -> void:
    sys.out.println_s("Hello: " ++ Any.toString(1223344566))
