import sys
def assertV(condition,message):
    manual=False
    if(condition):
        return
    if(not manual):
        sys.stderr.write(message)
        sys.exit(-1)
    else:
        raise Exception(message)
    


