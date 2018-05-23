type pos is (int x) where x >= 0
type neg is (int x) where x < 0

type A1 is ((pos|neg)[] x)

function f(pos[] xs) -> (A1 rs)
ensures xs == rs:
    return xs

public export method test():
    assert f([0,1,2]) == [0,1,2]