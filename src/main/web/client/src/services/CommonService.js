class CommonService {

    static Instance() {
        return new CommonService()
    }

    ifExist(array, attr, value) {
        for (let i = 0; i < array.length; i += 1) {
            if (array[i][attr] === value) {
                return true;
            }
        }
        return false;
    }

    findIndex(array, attr, value) {
        if (!array) {
            return -1;
        }
        for (let i = 0; i < array.length; i += 1) {
            if (array[i][attr] === value) {
                return i;
            }
        }
        return -1;
    }

    getSorted(array, attr, ascending) {
        array.sort((a, b) => this.compare(a, b, attr, ascending));
        return array;
    }

    compare(a, b, attr, ascending) {
        if (a[attr] > b[attr]) {
            if (ascending) {
                return 1;
            } else {
                return -1;
            }
        }
        if (b[attr] > a[attr]) {
            if (ascending) {
                return -1;
            } else {
                return 1;
            }
        }
        return 0;
    }
}


export default CommonService.Instance()