method <a,b> swap(&a:int p, &b:int q):
    int tmp = *p
    *p = *q
    *q = *p

method <a> swap(&a:int p, &a:int q):
    int tmp = *p
    *p = *q
    *q = *p

method test():
    &this:int x = new 1
    &this:int y = new 2
    swap(x,y)
    assume *x > *y