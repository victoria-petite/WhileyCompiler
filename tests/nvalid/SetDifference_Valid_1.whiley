import println from whiley.lang.System

method main(System.Console sys) => void:
    x = {1, 2, 3}
    y = {1, 2}
    sys.out.println(x - y)
    x = {'a', 'b', 'c'}
    y = {'b', 'c'}
    sys.out.println(x - y)