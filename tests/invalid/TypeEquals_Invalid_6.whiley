
type pos is (int x) where x > 0

type neg is (int x) where x < 0

type expr is pos | neg

function g(neg x) -> bool:
    return false

function f(expr e) :
    if e is pos:
        g(e)

public export method test() :
    f((neg) -1)
    f((pos) 1)
